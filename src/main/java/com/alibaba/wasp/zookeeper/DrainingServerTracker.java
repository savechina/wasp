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
package com.alibaba.wasp.zookeeper;

import com.alibaba.wasp.ServerName;
import com.alibaba.wasp.master.FServerManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Abortable;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;


/**
 * Tracks the list of draining fservers via ZK.
 * 
 * <p>
 * This class is responsible for watching for changes to the draining servers
 * list. It handles adds/deletes in the draining FS list and watches each node.
 * 
 * <p>
 * If an fs gets deleted from draining list, we call
 * {@link com.alibaba.wasp.master.FServerManager#removeServerFromDrainList(com.alibaba.wasp.ServerName)}
 *
 * <p>
 * If an fs gets added to the draining list, we add a watcher to it and call
 * {@link com.alibaba.wasp.master.FServerManager#addServerToDrainList(com.alibaba.wasp.ServerName)}
 *
 */
public class DrainingServerTracker extends ZooKeeperListener {
  private static final Log LOG = LogFactory.getLog(DrainingServerTracker.class);

  private FServerManager serverManager;
  private NavigableSet<ServerName> drainingServers = new TreeSet<ServerName>();
  private Abortable abortable;

  public DrainingServerTracker(ZooKeeperWatcher watcher,
      Abortable abortable, FServerManager serverManager) {
    super(watcher);
    this.abortable = abortable;
    this.serverManager = serverManager;
  }

  /**
   * Starts the tracking of draining fServers.
   *
   * <p>All Draining RSs will be tracked after this method is called.
   *
   * @throws org.apache.zookeeper.KeeperException
   */
  public void start() throws KeeperException, IOException {
    watcher.registerListener(this);
    List<String> servers =
      ZKUtil.listChildrenAndWatchThem(watcher, watcher.drainingZNode);
    add(servers);
  }

  private void add(final List<String> servers) throws IOException {
    synchronized(this.drainingServers) {
      this.drainingServers.clear();
      for (String n: servers) {
        final ServerName sn = new ServerName(ZKUtil.getNodeName(n));
        this.drainingServers.add(sn);
        this.serverManager.addServerToDrainList(sn);
        LOG.info("Draining RS node created, adding to list [" +
            sn + "]");

      }
    }
  }

  private void remove(final ServerName sn) {
    synchronized(this.drainingServers) {
      this.drainingServers.remove(sn);
      this.serverManager.removeServerFromDrainList(sn);
    }
  }

  @Override
  public void nodeDeleted(final String path) {
    if(path.startsWith(watcher.drainingZNode)) {
      final ServerName sn = new ServerName(ZKUtil.getNodeName(path));
      LOG.info("Draining RS node deleted, removing from list [" +
          sn + "]");
      remove(sn);
    }
  }

  @Override
  public void nodeChildrenChanged(final String path) {
    if(path.equals(watcher.drainingZNode)) {
      try {
        final List<String> newNodes =
          ZKUtil.listChildrenAndWatchThem(watcher, watcher.drainingZNode);
        add(newNodes);
      } catch (KeeperException e) {
        abortable.abort("Unexpected zk exception getting RS nodes", e);
      } catch (IOException e) {
        abortable.abort("Unexpected zk exception getting RS nodes", e);
      }
    }
  }


}
