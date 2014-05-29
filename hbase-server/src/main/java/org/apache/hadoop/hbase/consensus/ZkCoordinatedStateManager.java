/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.consensus;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.CoordinatedStateException;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.TableStateManager;
import org.apache.hadoop.hbase.zookeeper.ZKTableStateManager;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

/**
 * ZooKeeper-based implementation of {@link org.apache.hadoop.hbase.CoordinatedStateManager}.
 */
@InterfaceAudience.Private
public class ZkCoordinatedStateManager extends BaseCoordinatedStateManager {
  private static final Log LOG = LogFactory.getLog(ZkCoordinatedStateManager.class);
  private Server server;
  private ZooKeeperWatcher watcher;

  @Override
  public void initialize(Server server) {
    this.server = server;
    this.watcher = server.getZooKeeper();
  }

  @Override
  public Server getServer() {
    return server;
  }

  @Override
  public TableStateManager getTableStateManager() throws InterruptedException,
      CoordinatedStateException {
    try {
      return new ZKTableStateManager(server.getZooKeeper());
    } catch (KeeperException e) {
      throw new CoordinatedStateException(e);
    }
  }
}
