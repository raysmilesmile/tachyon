/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.master;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.curator.test.TestingServer;

import com.google.common.base.Supplier;
import com.google.common.base.Throwables;

import tachyon.Constants;
import tachyon.client.ClientContext;
import tachyon.client.file.TachyonFileSystem;
import tachyon.conf.TachyonConf;
import tachyon.exception.ConnectionFailedException;
import tachyon.underfs.UnderFileSystem;
import tachyon.util.LineageUtils;
import tachyon.worker.WorkerContext;

/**
 * A local Tachyon cluster with Multiple masters
 */
public class LocalTachyonClusterMultiMaster extends AbstractLocalTachyonCluster {

  private TestingServer mCuratorServer = null;
  private int mNumOfMasters = 0;

  private final List<LocalTachyonMaster> mMasters = new ArrayList<LocalTachyonMaster>();

  private final Supplier<String> mClientSuppliers = new Supplier<String>() {
    @Override
    public String get() {
      return getUri();
    }
  };
  private final ClientPool mClientPool = new ClientPool(mClientSuppliers);

  public LocalTachyonClusterMultiMaster(long workerCapacityBytes, int masters, int userBlockSize) {
    super(workerCapacityBytes, userBlockSize);
    mNumOfMasters = masters;

    try {
      mCuratorServer = new TestingServer();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public synchronized TachyonFileSystem getClient() throws IOException {
    return mClientPool.getClient(ClientContext.getConf());
  }

  public String getUri() {
    return new StringBuilder()
        .append(Constants.HEADER_FT)
        .append(mHostname)
        .append(":")
        .append(getMaster().getRPCLocalPort())
        .toString();
  }

  @Override
  public LocalTachyonMaster getMaster() {
    return mMasters.get(0);
  }

  /**
   * @return index of leader master in {@link #mMasters}, or -1 if there is no leader temporarily
   */
  public int getLeaderIndex() {
    for (int i = 0; i < mNumOfMasters; i ++) {
      if (mMasters.get(i).isServing()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Iterate over the masters in the order of master creation, kill the first standby master.
   *
   * @return true if a standby master is successfully killed, otherwise, false
   */
  public boolean killStandby() {
    for (int k = 0; k < mNumOfMasters; k ++) {
      if (!mMasters.get(k).isServing()) {
        try {
          LOG.info("master {} is a standby. killing it...", k);
          mMasters.get(k).stop();
          LOG.info("master {} killed.", k);
        } catch (Exception e) {
          LOG.error(e.getMessage(), e);
          return false;
        }
        return true;
      }
    }
    return false;
  }

  public boolean killLeader() {
    for (int k = 0; k < mNumOfMasters; k ++) {
      if (mMasters.get(k).isServing()) {
        try {
          LOG.info("master {} is the leader. killing it...", k);
          mMasters.get(k).stop();
          LOG.info("master {} killed.", k);
        } catch (Exception e) {
          LOG.error(e.getMessage(), e);
          return false;
        }
        return true;
      }
    }
    return false;
  }

  private void deleteDir(String path) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.get(path, mMasterConf);

    if (ufs.exists(path) && !ufs.delete(path, true)) {
      throw new IOException("Folder " + path + " already exists but can not be deleted.");
    }
  }

  private void mkdir(String path) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.get(path, mMasterConf);

    if (ufs.exists(path)) {
      ufs.delete(path, true);
    }
    if (!ufs.mkdirs(path, true)) {
      throw new IOException("Failed to make folder: " + path);
    }
  }

  @Override
  protected void startWorker(TachyonConf conf) throws IOException, ConnectionFailedException {
    mWorkerConf = WorkerContext.getConf();
    mWorkerConf.merge(conf);

    mWorkerConf.set(Constants.WORKER_WORKER_BLOCK_THREADS_MAX, "100");

    runWorker();
    // The client context should reflect the updates to the conf.
    ClientContext.reset(mWorkerConf);
  }

  @Override
  protected void setupTest(TachyonConf conf) throws IOException {}

  @Override
  protected void startMaster(TachyonConf conf) throws IOException {
    mMasterConf = conf;
    mMasterConf.set(Constants.ZOOKEEPER_ENABLED, "true");
    mMasterConf.set(Constants.ZOOKEEPER_ADDRESS, mCuratorServer.getConnectString());
    mMasterConf.set(Constants.ZOOKEEPER_ELECTION_PATH, "/election");
    mMasterConf.set(Constants.ZOOKEEPER_LEADER_PATH, "/leader");

    // re-build the dir to set permission to 777
    deleteDir(mTachyonHome);
    mkdir(mTachyonHome);

    for (int k = 0; k < mNumOfMasters; k ++) {
      final LocalTachyonMaster master = LocalTachyonMaster.create(mTachyonHome);
      master.start();
      LOG.info("master NO.{} started, isServing: {}, address: {}", k, master.isServing(),
          master.getAddress());
      mMasters.add(master);
      // Each master should generate a new port for binding
      mMasterConf.set(Constants.MASTER_RPC_PORT, "0");
    }

    // Create the UFS directory after LocalTachyonMaster construction, because LocalTachyonMaster
    // sets UNDERFS_ADDRESS.
    mkdir(mMasterConf.get(Constants.UNDERFS_ADDRESS));

    LOG.info("all {} masters started.", mNumOfMasters);
    LOG.info("waiting for a leader.");
    boolean hasLeader = false;
    while (!hasLeader) {
      for (int i = 0; i < mMasters.size(); i ++) {
        if (mMasters.get(i).isServing()) {
          LOG.info("master NO.{} is selected as leader. address: {}", i,
              mMasters.get(i).getAddress());
          hasLeader = true;
          break;
        }
      }
    }
    // Use first master port
    mMasterConf.set(Constants.MASTER_RPC_PORT, String.valueOf(getMaster().getRPCLocalPort()));
  }

  @Override
  public void stopTFS() throws Exception {
    mClientPool.close();

    mWorker.stop();
    if (LineageUtils.isLineageEnabled(WorkerContext.getConf())) {
      mLineageWorker.stop();
    }
    for (int k = 0; k < mNumOfMasters; k ++) {
      mMasters.get(k).stop();
    }
    mCuratorServer.stop();
  }

  @Override
  public void stopUFS() throws Exception {
    // masters share underfs, so only need to call on the first master
    mMasters.get(0).cleanupUnderfs();
  }
}
