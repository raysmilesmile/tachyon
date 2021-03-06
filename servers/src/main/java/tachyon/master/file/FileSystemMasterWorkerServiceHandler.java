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

package tachyon.master.file;

import java.util.Set;

import com.google.common.base.Preconditions;

import tachyon.Constants;
import tachyon.exception.TachyonException;
import tachyon.thrift.FileInfo;
import tachyon.thrift.FileSystemMasterWorkerService;
import tachyon.thrift.TachyonTException;

/**
 * This class is a Thrift handler for file system master RPCs invoked by a Tachyon worker.
 */
public final class FileSystemMasterWorkerServiceHandler implements
    FileSystemMasterWorkerService.Iface {
  private final FileSystemMaster mFileSystemMaster;

  public FileSystemMasterWorkerServiceHandler(FileSystemMaster fileSystemMaster) {
    Preconditions.checkNotNull(fileSystemMaster);
    mFileSystemMaster = fileSystemMaster;
  }

  @Override
  public long getServiceVersion() {
    return Constants.FILE_SYSTEM_MASTER_WORKER_SERVICE_VERSION;
  }

  @Override
  public FileInfo getFileInfo(long fileId) throws TachyonTException {
    try {
      return mFileSystemMaster.getFileInfo(fileId);
    } catch (TachyonException e) {
      throw e.toTachyonTException();
    }
  }

  @Override
  public Set<Long> getPinIdList() {
    return mFileSystemMaster.getPinIdList();
  }
}
