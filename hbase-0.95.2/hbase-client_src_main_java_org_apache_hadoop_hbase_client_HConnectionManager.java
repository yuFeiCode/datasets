/**
*
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
package org.apache.hadoop.hbase.client;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Chore;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.MetaScanner.MetaScannerVisitor;
import org.apache.hadoop.hbase.client.MetaScanner.MetaScannerVisitorBase;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.exceptions.RegionMovedException;
import org.apache.hadoop.hbase.exceptions.RegionOpeningException;
import org.apache.hadoop.hbase.ipc.RpcClient;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ClientService;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.CoprocessorServiceRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.CoprocessorServiceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyNamespaceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyNamespaceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CreateNamespaceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CreateNamespaceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteNamespaceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteNamespaceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.GetNamespaceDescriptorResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.GetNamespaceDescriptorRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListNamespaceDescriptorsResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListNamespaceDescriptorsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListTableDescriptorsByNamespaceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListTableDescriptorsByNamespaceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListTableNamesByNamespaceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos
.ListTableNamesByNamespaceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AddColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AddColumnResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AssignRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.AssignRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.BalanceRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.BalanceResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CatalogScanRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CatalogScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CreateTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CreateTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteColumnResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DeleteTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DisableTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DisableTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DispatchMergingRegionsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.DispatchMergingRegionsResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableCatalogJanitorRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableCatalogJanitorResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsCatalogJanitorEnabledRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsCatalogJanitorEnabledResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsRestoreSnapshotDoneRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsRestoreSnapshotDoneResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsSnapshotDoneRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.IsSnapshotDoneResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ListSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ListSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.MasterAdminService;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyColumnRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyColumnResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ModifyTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.MoveRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.MoveRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.OfflineRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.OfflineRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.RestoreSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.RestoreSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.SetBalancerRunningRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.SetBalancerRunningResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ShutdownRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.ShutdownResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.StopMasterRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.StopMasterResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.TakeSnapshotRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.TakeSnapshotResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.UnassignRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.UnassignRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetClusterStatusRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetClusterStatusResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetSchemaAlterStatusRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetSchemaAlterStatusResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableDescriptorsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableDescriptorsResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableNamesRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.GetTableNamesResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterMonitorProtos.MasterMonitorService;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.IsMasterRunningRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.IsMasterRunningResponse;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.SoftValueSortedMap;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.MasterAddressTracker;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.BlockingRpcChannel;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
* A non-instantiable class that manages creation of {@link HConnection}s.
* <p>The simplest way to use this class is by using {@link #createConnection(Configuration)}.
* This creates a new {@link HConnection} that is managed by the caller.
* From this {@link HConnection} {@link HTableInterface} implementations are retrieved
* with {@link HConnection#getTable(byte[])}. Example:
* <pre>
* {@code
* HConnection connection = HConnectionManager.createConnection(config);
* HTableInterface table = connection.getTable("table1");
* // use the table as needed, for a single operation and a single thread
* table.close();
* connection.close();
* }
* </pre>
* <p>The following logic and API will be removed in the future:
* <p>This class has a static Map of {@link HConnection} instances keyed by
* {@link Configuration}; all invocations of {@link #getConnection(Configuration)}
* that pass the same {@link Configuration} instance will be returned the same
* {@link  HConnection} instance (Adding properties to a Configuration
* instance does not change its object identity; for more on how this is done see
* {@link HConnectionKey}).  Sharing {@link HConnection}
* instances is usually what you want; all clients of the {@link HConnection}
* instances share the HConnections' cache of Region locations rather than each
* having to discover for itself the location of meta, etc.  It makes
* sense for the likes of the pool of HTables class {@link HTablePool}, for
* instance (If concerned that a single {@link HConnection} is insufficient
* for sharing amongst clients in say an heavily-multithreaded environment,
* in practise its not proven to be an issue.  Besides, {@link HConnection} is
* implemented atop Hadoop RPC and as of this writing, Hadoop RPC does a
* connection per cluster-member, exclusively).
*
* <p>But sharing connections makes clean up of {@link HConnection} instances a little awkward.
* Currently, clients cleanup by calling {@link #deleteConnection(Configuration)}. This will
* shutdown the zookeeper connection the HConnection was using and clean up all
* HConnection resources as well as stopping proxies to servers out on the
* cluster. Not running the cleanup will not end the world; it'll
* just stall the closeup some and spew some zookeeper connection failed
* messages into the log.  Running the cleanup on a {@link HConnection} that is
* subsequently used by another will cause breakage so be careful running
* cleanup.
* <p>To create a {@link HConnection} that is not shared by others, you can
* create a new {@link Configuration} instance, pass this new instance to
* {@link #getConnection(Configuration)}, and then when done, close it up by
* doing something like the following:
* <pre>
* {@code
* Configuration newConfig = new Configuration(originalConf);
* HConnection connection = HConnectionManager.getConnection(newConfig);
* // Use the connection to your hearts' delight and then when done...
* HConnectionManager.deleteConnection(newConfig, true);
* }
* </pre>
* <p>Cleanup used to be done inside in a shutdown hook.  On startup we'd
* register a shutdown hook that called {@link #deleteAllConnections()}
* on its way out but the order in which shutdown hooks run is not defined so
* were problematic for clients of HConnection that wanted to register their
* own shutdown hooks so we removed ours though this shifts the onus for
* cleanup to the client.
*/
@SuppressWarnings("serial")
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class HConnectionManager {
static final Log LOG = LogFactory.getLog(HConnectionManager.class);

public static final String RETRIES_BY_SERVER_KEY = "hbase.client.retries.by.server";

// An LRU Map of HConnectionKey -> HConnection (TableServer).  All
// access must be synchronized.  This map is not private because tests
// need to be able to tinker with it.
static final Map<HConnectionKey, HConnectionImplementation> CONNECTION_INSTANCES;

public static final int MAX_CACHED_CONNECTION_INSTANCES;

static {
// We set instances to one more than the value specified for {@link
// HConstants#ZOOKEEPER_MAX_CLIENT_CNXNS}. By default, the zk default max
// connections to the ensemble from the one client is 30, so in that case we
// should run into zk issues before the LRU hit this value of 31.
MAX_CACHED_CONNECTION_INSTANCES = HBaseConfiguration.create().getInt(
HConstants.ZOOKEEPER_MAX_CLIENT_CNXNS, HConstants.DEFAULT_ZOOKEPER_MAX_CLIENT_CNXNS) + 1;
CONNECTION_INSTANCES = new LinkedHashMap<HConnectionKey, HConnectionImplementation>(
(int) (MAX_CACHED_CONNECTION_INSTANCES / 0.75F) + 1, 0.75F, true) {
@Override
protected boolean removeEldestEntry(
Map.Entry<HConnectionKey, HConnectionImplementation> eldest) {
return size() > MAX_CACHED_CONNECTION_INSTANCES;
}
};
}

/*
* Non-instantiable.
*/
private HConnectionManager() {
super();
}

/**
* Get the connection that goes with the passed <code>conf</code> configuration instance.
* If no current connection exists, method creates a new connection and keys it using
* connection-specific properties from the passed {@link Configuration}; see
* {@link HConnectionKey}.
* @param conf configuration
* @return HConnection object for <code>conf</code>
* @throws ZooKeeperConnectionException
*/
@Deprecated
@SuppressWarnings("resource")
public static HConnection getConnection(final Configuration conf)
throws IOException {
HConnectionKey connectionKey = new HConnectionKey(conf);
synchronized (CONNECTION_INSTANCES) {
HConnectionImplementation connection = CONNECTION_INSTANCES.get(connectionKey);
if (connection == null) {
connection = (HConnectionImplementation)createConnection(conf, true);
CONNECTION_INSTANCES.put(connectionKey, connection);
} else if (connection.isClosed()) {
HConnectionManager.deleteConnection(connectionKey, true);
connection = (HConnectionImplementation)createConnection(conf, true);
CONNECTION_INSTANCES.put(connectionKey, connection);
}
connection.incCount();
return connection;
}
}

/**
* Create a new HConnection instance using the passed <code>conf</code> instance.
* <p>Note: This bypasses the usual HConnection life cycle management done by
* {@link #getConnection(Configuration)}. The caller is responsible for
* calling {@link HConnection#close()} on the returned connection instance.
*
* This is the recommended way to create HConnections.
* {@code
* HConnection connection = HConnectionManager.createConnection(conf);
* HTableInterface table = connection.getTable("mytable");
* table.get(...);
* ...
* table.close();
* connection.close();
* }
*
* @param conf configuration
* @return HConnection object for <code>conf</code>
* @throws ZooKeeperConnectionException
*/
public static HConnection createConnection(Configuration conf)
throws IOException {
return createConnection(conf, false, null);
}

/**
* Create a new HConnection instance using the passed <code>conf</code> instance.
* <p>Note: This bypasses the usual HConnection life cycle management done by
* {@link #getConnection(Configuration)}. The caller is responsible for
* calling {@link HConnection#close()} on the returned connection instance.
* This is the recommended way to create HConnections.
* {@code
* ExecutorService pool = ...;
* HConnection connection = HConnectionManager.createConnection(conf, pool);
* HTableInterface table = connection.getTable("mytable");
* table.get(...);
* ...
* table.close();
* connection.close();
* }
* @param conf configuration
* @param pool the thread pool to use for batch operation in HTables used via this HConnection
* @return HConnection object for <code>conf</code>
* @throws ZooKeeperConnectionException
*/
public static HConnection createConnection(Configuration conf, ExecutorService pool)
throws IOException {
return createConnection(conf, false, pool);
}

@Deprecated
static HConnection createConnection(final Configuration conf, final boolean managed)
throws IOException {
return createConnection(conf, managed, null);
}

@Deprecated
static HConnection createConnection(final Configuration conf, final boolean managed, final ExecutorService pool)
throws IOException {
String className = conf.get("hbase.client.connection.impl",
HConnectionManager.HConnectionImplementation.class.getName());
Class<?> clazz = null;
try {
clazz = Class.forName(className);
} catch (ClassNotFoundException e) {
throw new IOException(e);
}
try {
// Default HCM#HCI is not accessible; make it so before invoking.
Constructor<?> constructor =
clazz.getDeclaredConstructor(Configuration.class, boolean.class, ExecutorService.class);
constructor.setAccessible(true);
return (HConnection) constructor.newInstance(conf, managed, pool);
} catch (Exception e) {
throw new IOException(e);
}
}

/**
* Delete connection information for the instance specified by passed configuration.
* If there are no more references to the designated connection connection, this method will
* then close connection to the zookeeper ensemble and let go of all associated resources.
*
* @param conf configuration whose identity is used to find {@link HConnection} instance.
* @deprecated
*/
public static void deleteConnection(Configuration conf) {
deleteConnection(new HConnectionKey(conf), false);
}

/**
* Cleanup a known stale connection.
* This will then close connection to the zookeeper ensemble and let go of all resources.
*
* @param connection
* @deprecated
*/
public static void deleteStaleConnection(HConnection connection) {
deleteConnection(connection, true);
}

/**
* Delete information for all connections. Close or not the connection, depending on the
*  staleConnection boolean and the ref count. By default, you should use it with
*  staleConnection to true.
* @deprecated
*/
public static void deleteAllConnections(boolean staleConnection) {
synchronized (CONNECTION_INSTANCES) {
Set<HConnectionKey> connectionKeys = new HashSet<HConnectionKey>();
connectionKeys.addAll(CONNECTION_INSTANCES.keySet());
for (HConnectionKey connectionKey : connectionKeys) {
deleteConnection(connectionKey, staleConnection);
}
CONNECTION_INSTANCES.clear();
}
}

/**
* Delete information for all connections..
* @deprecated kept for backward compatibility, but the behavior is broken. HBASE-8983
*/
@Deprecated
public static void deleteAllConnections() {
deleteAllConnections(false);
}


@Deprecated
private static void deleteConnection(HConnection connection, boolean staleConnection) {
synchronized (CONNECTION_INSTANCES) {
for (Entry<HConnectionKey, HConnectionImplementation> e: CONNECTION_INSTANCES.entrySet()) {
if (e.getValue() == connection) {
deleteConnection(e.getKey(), staleConnection);
break;
}
}
}
}

@Deprecated
private static void deleteConnection(HConnectionKey connectionKey, boolean staleConnection) {
synchronized (CONNECTION_INSTANCES) {
HConnectionImplementation connection = CONNECTION_INSTANCES.get(connectionKey);
if (connection != null) {
connection.decCount();
if (connection.isZeroReference() || staleConnection) {
CONNECTION_INSTANCES.remove(connectionKey);
connection.internalClose();
}
} else {
LOG.error("Connection not found in the list, can't delete it "+
"(connection key=" + connectionKey + "). May be the key was modified?", new Exception());
}
}
}

/**
* It is provided for unit test cases which verify the behavior of region
* location cache prefetch.
* @return Number of cached regions for the table.
* @throws ZooKeeperConnectionException
*/
static int getCachedRegionCount(Configuration conf, final TableName tableName)
throws IOException {
return execute(new HConnectable<Integer>(conf) {
@Override
public Integer connect(HConnection connection) {
return ((HConnectionImplementation)connection).getNumberOfCachedRegionLocations(tableName);
}
});
}

/**
* It's provided for unit test cases which verify the behavior of region
* location cache prefetch.
* @return true if the region where the table and row reside is cached.
* @throws ZooKeeperConnectionException
*/
static boolean isRegionCached(Configuration conf,
final TableName tableName,
final byte[] row)
throws IOException {
return execute(new HConnectable<Boolean>(conf) {
@Override
public Boolean connect(HConnection connection) {
return ((HConnectionImplementation) connection).isRegionCached(tableName, row);
}
});
}

/**
* This convenience method invokes the given {@link HConnectable#connect}
* implementation using a {@link HConnection} instance that lasts just for the
* duration of the invocation.
*
* @param <T> the return type of the connect method
* @param connectable the {@link HConnectable} instance
* @return the value returned by the connect method
* @throws IOException
*/
public static <T> T execute(HConnectable<T> connectable) throws IOException {
if (connectable == null || connectable.conf == null) {
return null;
}
Configuration conf = connectable.conf;
HConnection connection = HConnectionManager.getConnection(conf);
boolean connectSucceeded = false;
try {
T returnValue = connectable.connect(connection);
connectSucceeded = true;
return returnValue;
} finally {
try {
connection.close();
} catch (Exception e) {
if (connectSucceeded) {
throw new IOException("The connection to " + connection
+ " could not be deleted.", e);
}
}
}
}

/** Encapsulates connection to zookeeper and regionservers.*/
@edu.umd.cs.findbugs.annotations.SuppressWarnings(
value="AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION",
justification="Access to the conncurrent hash map is under a lock so should be fine.")
static class HConnectionImplementation implements HConnection, Closeable {
static final Log LOG = LogFactory.getLog(HConnectionImplementation.class);
private final long pause;
private final int numTries;
final int rpcTimeout;
private final int prefetchRegionLimit;

private volatile boolean closed;
private volatile boolean aborted;

// package protected for the tests
ClusterStatusListener clusterStatusListener;

private final Object userRegionLock = new Object();

// We have a single lock for master & zk to prevent deadlocks. Having
//  one lock for ZK and one lock for master is not possible:
//  When creating a connection to master, we need a connection to ZK to get
//  its address. But another thread could have taken the ZK lock, and could
//  be waiting for the master lock => deadlock.
private final Object masterAndZKLock = new Object();

private long keepZooKeeperWatcherAliveUntil = Long.MAX_VALUE;
private final DelayedClosing delayedClosing =
DelayedClosing.createAndStart(this);

// thread executor shared by all HTableInterface instances created
// by this connection
private volatile ExecutorService batchPool = null;
private volatile boolean cleanupPool = false;

private final Configuration conf;

// Client rpc instance.
private RpcClient rpcClient;

/**
* Map of table to table {@link HRegionLocation}s.
*/
private final Map<TableName, SoftValueSortedMap<byte[], HRegionLocation>>
cachedRegionLocations =
new HashMap<TableName, SoftValueSortedMap<byte[], HRegionLocation>>();

// The presence of a server in the map implies it's likely that there is an
// entry in cachedRegionLocations that map to this server; but the absence
// of a server in this map guarentees that there is no entry in cache that
// maps to the absent server.
// The access to this attribute must be protected by a lock on cachedRegionLocations
private final Set<ServerName> cachedServers = new HashSet<ServerName>();

// region cache prefetch is enabled by default. this set contains all
// tables whose region cache prefetch are disabled.
private final Set<Integer> regionCachePrefetchDisabledTables =
new CopyOnWriteArraySet<Integer>();

private int refCount;

// indicates whether this connection's life cycle is managed (by us)
private boolean managed;

/**
* Cluster registry of basic info such as clusterid and meta region location.
*/
Registry registry;

HConnectionImplementation(Configuration conf, boolean managed) throws IOException {
this(conf, managed, null);
}

/**
* constructor
* @param conf Configuration object
* @param managed If true, does not do full shutdown on close; i.e. cleanup of connection
* to zk and shutdown of all services; we just close down the resources this connection was
* responsible for and decrement usage counters.  It is up to the caller to do the full
* cleanup.  It is set when we want have connection sharing going on -- reuse of zk connection,
* and cached region locations, established regionserver connections, etc.  When connections
* are shared, we have reference counting going on and will only do full cleanup when no more
* users of an HConnectionImplementation instance.
*/
HConnectionImplementation(Configuration conf, boolean managed, ExecutorService pool) throws IOException {
this(conf);
this.batchPool = pool;
this.managed = managed;
this.registry = setupRegistry();
retrieveClusterId();

this.rpcClient = new RpcClient(this.conf, this.clusterId);

// Do we publish the status?
Class<? extends ClusterStatusListener.Listener> listenerClass =
conf.getClass(ClusterStatusListener.STATUS_LISTENER_CLASS,
ClusterStatusListener.DEFAULT_STATUS_LISTENER_CLASS,
ClusterStatusListener.Listener.class);

if (listenerClass != null) {
clusterStatusListener = new ClusterStatusListener(
new ClusterStatusListener.DeadServerHandler() {
@Override
public void newDead(ServerName sn) {
clearCaches(sn);
rpcClient.cancelConnections(sn.getHostname(), sn.getPort(),
new SocketException(sn.getServerName() + " is dead: closing its connection."));
}
}, conf, listenerClass);
}
}


/**
* For tests.
*/
protected HConnectionImplementation(Configuration conf) {
this.conf = conf;
this.closed = false;
this.pause = conf.getLong(HConstants.HBASE_CLIENT_PAUSE,
HConstants.DEFAULT_HBASE_CLIENT_PAUSE);
this.numTries = conf.getInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
HConstants.DEFAULT_HBASE_CLIENT_RETRIES_NUMBER);
this.rpcTimeout = conf.getInt(
HConstants.HBASE_RPC_TIMEOUT_KEY,
HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
this.prefetchRegionLimit = conf.getInt(
HConstants.HBASE_CLIENT_PREFETCH_LIMIT,
HConstants.DEFAULT_HBASE_CLIENT_PREFETCH_LIMIT);
}

@Override
public HTableInterface getTable(String tableName) throws IOException {
return getTable(TableName.valueOf(tableName));
}

@Override
public HTableInterface getTable(byte[] tableName) throws IOException {
return getTable(TableName.valueOf(tableName));
}

@Override
public HTableInterface getTable(TableName tableName) throws IOException {
return getTable(tableName, getBatchPool());
}

@Override
public HTableInterface getTable(String tableName, ExecutorService pool) throws IOException {
return getTable(TableName.valueOf(tableName), pool);
}

@Override
public HTableInterface getTable(byte[] tableName, ExecutorService pool) throws IOException {
return getTable(TableName.valueOf(tableName), pool);
}

@Override
public HTableInterface getTable(TableName tableName, ExecutorService pool) throws IOException {
if (managed) {
throw new IOException("The connection has to be unmanaged.");
}
return new HTable(tableName, this, pool);
}

private ExecutorService getBatchPool() {
if (batchPool == null) {
// shared HTable thread executor not yet initialized
synchronized (this) {
if (batchPool == null) {
int maxThreads = conf.getInt("hbase.hconnection.threads.max",
Integer.MAX_VALUE);
if (maxThreads == 0) {
maxThreads = Runtime.getRuntime().availableProcessors();
}
long keepAliveTime = conf.getLong(
"hbase.hconnection.threads.keepalivetime", 60);
this.batchPool = new ThreadPoolExecutor(
Runtime.getRuntime().availableProcessors(),
maxThreads,
keepAliveTime,
TimeUnit.SECONDS,
new SynchronousQueue<Runnable>(),
Threads.newDaemonThreadFactory("hbase-connection-shared-executor"));
((ThreadPoolExecutor) this.batchPool)
.allowCoreThreadTimeOut(true);
}
this.cleanupPool = true;
}
}
return this.batchPool;
}

protected ExecutorService getCurrentBatchPool() {
return batchPool;
}

private void shutdownBatchPool() {
if (this.cleanupPool && this.batchPool != null && !this.batchPool.isShutdown()) {
this.batchPool.shutdown();
try {
if (!this.batchPool.awaitTermination(10, TimeUnit.SECONDS)) {
this.batchPool.shutdownNow();
}
} catch (InterruptedException e) {
this.batchPool.shutdownNow();
}
}
}

/**
* @return The cluster registry implementation to use.
* @throws IOException
*/
private Registry setupRegistry() throws IOException {
String registryClass = this.conf.get("hbase.client.registry.impl",
ZooKeeperRegistry.class.getName());
Registry registry = null;
try {
registry = (Registry)Class.forName(registryClass).newInstance();
} catch (Throwable t) {
throw new IOException(t);
}
registry.init(this);
return registry;
}

/**
* For tests only.
* @param rpcClient Client we should use instead.
* @return Previous rpcClient
*/
RpcClient setRpcClient(final RpcClient rpcClient) {
RpcClient oldRpcClient = this.rpcClient;
this.rpcClient = rpcClient;
return oldRpcClient;
}

/**
* An identifier that will remain the same for a given connection.
* @return
*/
public String toString(){
return "hconnection-0x" + Integer.toHexString(hashCode());
}

protected String clusterId = null;

void retrieveClusterId() {
if (clusterId != null) return;
this.clusterId = this.registry.getClusterId();
if (clusterId == null) {
clusterId = HConstants.CLUSTER_ID_DEFAULT;
LOG.debug("clusterid came back null, using default " + clusterId);
}
}

@Override
public Configuration getConfiguration() {
return this.conf;
}

private void checkIfBaseNodeAvailable(ZooKeeperWatcher zkw)
throws MasterNotRunningException {
String errorMsg;
try {
if (ZKUtil.checkExists(zkw, zkw.baseZNode) == -1) {
errorMsg = "The node " + zkw.baseZNode+" is not in ZooKeeper. "
+ "It should have been written by the master. "
+ "Check the value configured in 'zookeeper.znode.parent'. "
+ "There could be a mismatch with the one configured in the master.";
LOG.error(errorMsg);
throw new MasterNotRunningException(errorMsg);
}
} catch (KeeperException e) {
errorMsg = "Can't get connection to ZooKeeper: " + e.getMessage();
LOG.error(errorMsg);
throw new MasterNotRunningException(errorMsg, e);
}
}

/**
* @return true if the master is running, throws an exception otherwise
* @throws MasterNotRunningException - if the master is not running
* @throws ZooKeeperConnectionException
*/
@Override
public boolean isMasterRunning()
throws MasterNotRunningException, ZooKeeperConnectionException {
// When getting the master connection, we check it's running,
// so if there is no exception, it means we've been able to get a
// connection on a running master
MasterMonitorKeepAliveConnection m = getKeepAliveMasterMonitorService();
try {
m.close();
} catch (IOException e) {
throw new MasterNotRunningException("Failed close", e);
}
return true;
}

@Override
public HRegionLocation getRegionLocation(final TableName tableName,
final byte [] row, boolean reload)
throws IOException {
return reload? relocateRegion(tableName, row): locateRegion(tableName, row);
}

@Override
public HRegionLocation getRegionLocation(final byte[] tableName,
final byte [] row, boolean reload)
throws IOException {
return getRegionLocation(TableName.valueOf(tableName), row, reload);
}

@Override
public boolean isTableEnabled(TableName tableName) throws IOException {
return this.registry.isTableOnlineState(tableName, true);
}

@Override
public boolean isTableEnabled(byte[] tableName) throws IOException {
return isTableEnabled(TableName.valueOf(tableName));
}

@Override
public boolean isTableDisabled(TableName tableName) throws IOException {
return this.registry.isTableOnlineState(tableName, false);
}

@Override
public boolean isTableDisabled(byte[] tableName) throws IOException {
return isTableDisabled(TableName.valueOf(tableName));
}

@Override
public boolean isTableAvailable(final TableName tableName) throws IOException {
final AtomicBoolean available = new AtomicBoolean(true);
final AtomicInteger regionCount = new AtomicInteger(0);
MetaScannerVisitor visitor = new MetaScannerVisitorBase() {
@Override
public boolean processRow(Result row) throws IOException {
HRegionInfo info = MetaScanner.getHRegionInfo(row);
if (info != null) {
if (tableName.equals(info.getTableName())) {
ServerName server = HRegionInfo.getServerName(row);
if (server == null) {
available.set(false);
return false;
}
regionCount.incrementAndGet();
} else if (tableName.compareTo(
info.getTableName()) < 0) {
// Return if we are done with the current table
return false;
}
}
return true;
}
};
MetaScanner.metaScan(conf, this, visitor, tableName);
return available.get() && (regionCount.get() > 0);
}

@Override
public boolean isTableAvailable(final byte[] tableName) throws IOException {
return isTableAvailable(TableName.valueOf(tableName));
}

@Override
public boolean isTableAvailable(final TableName tableName, final byte[][] splitKeys)
throws IOException {
final AtomicBoolean available = new AtomicBoolean(true);
final AtomicInteger regionCount = new AtomicInteger(0);
MetaScannerVisitor visitor = new MetaScannerVisitorBase() {
@Override
public boolean processRow(Result row) throws IOException {
HRegionInfo info = MetaScanner.getHRegionInfo(row);
if (info != null) {
if (tableName.equals(info.getTableName())) {
ServerName server = HRegionInfo.getServerName(row);
if (server == null) {
available.set(false);
return false;
}
if (!Bytes.equals(info.getStartKey(), HConstants.EMPTY_BYTE_ARRAY)) {
for (byte[] splitKey : splitKeys) {
// Just check if the splitkey is available
if (Bytes.equals(info.getStartKey(), splitKey)) {
regionCount.incrementAndGet();
break;
}
}
} else {
// Always empty start row should be counted
regionCount.incrementAndGet();
}
} else if (tableName.compareTo(info.getTableName()) < 0) {
// Return if we are done with the current table
return false;
}
}
return true;
}
};
MetaScanner.metaScan(conf, this, visitor, tableName);
// +1 needs to be added so that the empty start row is also taken into account
return available.get() && (regionCount.get() == splitKeys.length + 1);
}

@Override
public boolean isTableAvailable(final byte[] tableName, final byte[][] splitKeys)
throws IOException {
return isTableAvailable(TableName.valueOf(tableName), splitKeys);
}

@Override
public HRegionLocation locateRegion(final byte[] regionName) throws IOException {
return locateRegion(HRegionInfo.getTableName(regionName),
HRegionInfo.getStartKey(regionName), false, true);
}

@Override
public boolean isDeadServer(ServerName sn) {
if (clusterStatusListener == null) {
return false;
} else {
return clusterStatusListener.isDeadServer(sn);
}
}

@Override
public List<HRegionLocation> locateRegions(final TableName tableName)
throws IOException {
return locateRegions (tableName, false, true);
}

@Override
public List<HRegionLocation> locateRegions(final byte[] tableName)
throws IOException {
return locateRegions(TableName.valueOf(tableName));
}

@Override
public List<HRegionLocation> locateRegions(final TableName tableName,
final boolean useCache, final boolean offlined) throws IOException {
NavigableMap<HRegionInfo, ServerName> regions = MetaScanner.allTableRegions(conf, this,
tableName, offlined);
final List<HRegionLocation> locations = new ArrayList<HRegionLocation>();
for (HRegionInfo regionInfo : regions.keySet()) {
locations.add(locateRegion(tableName, regionInfo.getStartKey(), useCache, true));
}
return locations;
}

@Override
public List<HRegionLocation> locateRegions(final byte[] tableName,
final boolean useCache, final boolean offlined) throws IOException {
return locateRegions(TableName.valueOf(tableName), useCache, offlined);
}

@Override
public HRegionLocation locateRegion(final TableName tableName,
final byte [] row)
throws IOException{
return locateRegion(tableName, row, true, true);
}

@Override
public HRegionLocation locateRegion(final byte[] tableName,
final byte [] row)
throws IOException{
return locateRegion(TableName.valueOf(tableName), row);
}

@Override
public HRegionLocation relocateRegion(final TableName tableName,
final byte [] row) throws IOException{
// Since this is an explicit request not to use any caching, finding
// disabled tables should not be desirable.  This will ensure that an exception is thrown when
// the first time a disabled table is interacted with.
if (isTableDisabled(tableName)) {
throw new DoNotRetryIOException(tableName.getNameAsString() + " is disabled.");
}

return locateRegion(tableName, row, false, true);
}

@Override
public HRegionLocation relocateRegion(final byte[] tableName,
final byte [] row) throws IOException {
return relocateRegion(TableName.valueOf(tableName), row);
}


private HRegionLocation locateRegion(final TableName tableName,
final byte [] row, boolean useCache, boolean retry)
throws IOException {
if (this.closed) throw new IOException(toString() + " closed");
if (tableName== null || tableName.getName().length == 0) {
throw new IllegalArgumentException(
"table name cannot be null or zero length");
}

if (tableName.equals(TableName.META_TABLE_NAME)) {
return this.registry.getMetaRegionLocation();
} else {
// Region not in the cache - have to go to the meta RS
return locateRegionInMeta(TableName.META_TABLE_NAME, tableName, row,
useCache, userRegionLock, retry);
}
}

/*
* Search .META. for the HRegionLocation info that contains the table and
* row we're seeking. It will prefetch certain number of regions info and
* save them to the global region cache.
*/
private void prefetchRegionCache(final TableName tableName,
final byte[] row) {
// Implement a new visitor for MetaScanner, and use it to walk through
// the .META.
MetaScannerVisitor visitor = new MetaScannerVisitorBase() {
public boolean processRow(Result result) throws IOException {
try {
HRegionInfo regionInfo = MetaScanner.getHRegionInfo(result);
if (regionInfo == null) {
return true;
}

// possible we got a region of a different table...
if (!regionInfo.getTableName().equals(tableName)) {
return false; // stop scanning
}
if (regionInfo.isOffline()) {
// don't cache offline regions
return true;
}

ServerName serverName = HRegionInfo.getServerName(result);
if (serverName == null) {
return true; // don't cache it
}
// instantiate the location
long seqNum = HRegionInfo.getSeqNumDuringOpen(result);
HRegionLocation loc = new HRegionLocation(regionInfo, serverName, seqNum);
// cache this meta entry
cacheLocation(tableName, null, loc);
return true;
} catch (RuntimeException e) {
throw new IOException(e);
}
}
};
try {
// pre-fetch certain number of regions info at region cache.
MetaScanner.metaScan(conf, this, visitor, tableName, row,
this.prefetchRegionLimit, TableName.META_TABLE_NAME);
} catch (IOException e) {
LOG.warn("Encountered problems when prefetch META table: ", e);
}
}

/*
* Search the .META. table for the HRegionLocation
* info that contains the table and row we're seeking.
*/
private HRegionLocation locateRegionInMeta(final TableName parentTable,
final TableName tableName, final byte [] row, boolean useCache,
Object regionLockObject, boolean retry)
throws IOException {
HRegionLocation location;
// If we are supposed to be using the cache, look in the cache to see if
// we already have the region.
if (useCache) {
location = getCachedLocation(tableName, row);
if (location != null) {
return location;
}
}
int localNumRetries = retry ? numTries : 1;
// build the key of the meta region we should be looking for.
// the extra 9's on the end are necessary to allow "exact" matches
// without knowing the precise region names.
byte [] metaKey = HRegionInfo.createRegionName(tableName, row,
HConstants.NINES, false);
for (int tries = 0; true; tries++) {
if (tries >= localNumRetries) {
throw new NoServerForRegionException("Unable to find region for "
+ Bytes.toStringBinary(row) + " after " + numTries + " tries.");
}

HRegionLocation metaLocation = null;
try {
// locate the meta region
metaLocation = locateRegion(parentTable, metaKey, true, false);
// If null still, go around again.
if (metaLocation == null) continue;
ClientService.BlockingInterface service = getClient(metaLocation.getServerName());

Result regionInfoRow;
// This block guards against two threads trying to load the meta
// region at the same time. The first will load the meta region and
// the second will use the value that the first one found.
synchronized (regionLockObject) {
// Check the cache again for a hit in case some other thread made the
// same query while we were waiting on the lock.
if (useCache) {
location = getCachedLocation(tableName, row);
if (location != null) {
return location;
}
// If the parent table is META, we may want to pre-fetch some
// region info into the global region cache for this table.
if (parentTable.equals(TableName.META_TABLE_NAME)
&& (getRegionCachePrefetch(tableName))) {
prefetchRegionCache(tableName, row);
}
location = getCachedLocation(tableName, row);
if (location != null) {
return location;
}
} else {
// If we are not supposed to be using the cache, delete any existing cached location
// so it won't interfere.
forceDeleteCachedLocation(tableName, row);
}
// Query the meta region for the location of the meta region
regionInfoRow = ProtobufUtil.getRowOrBefore(service,
metaLocation.getRegionInfo().getRegionName(), metaKey,
HConstants.CATALOG_FAMILY);
}
if (regionInfoRow == null) {
throw new TableNotFoundException(tableName);
}

// convert the row result into the HRegionLocation we need!
HRegionInfo regionInfo = MetaScanner.getHRegionInfo(regionInfoRow);
if (regionInfo == null) {
throw new IOException("HRegionInfo was null or empty in " +
parentTable + ", row=" + regionInfoRow);
}

// possible we got a region of a different table...
if (!regionInfo.getTableName().equals(tableName)) {
throw new TableNotFoundException(
"Table '" + tableName + "' was not found, got: " +
regionInfo.getTableName() + ".");
}
if (regionInfo.isSplit()) {
throw new RegionOfflineException("the only available region for" +
" the required row is a split parent," +
" the daughters should be online soon: " +
regionInfo.getRegionNameAsString());
}
if (regionInfo.isOffline()) {
throw new RegionOfflineException("the region is offline, could" +
" be caused by a disable table call: " +
regionInfo.getRegionNameAsString());
}

ServerName serverName = HRegionInfo.getServerName(regionInfoRow);
if (serverName == null) {
throw new NoServerForRegionException("No server address listed " +
"in " + parentTable + " for region " +
regionInfo.getRegionNameAsString() + " containing row " +
Bytes.toStringBinary(row));
}

if (isDeadServer(serverName)){
throw new RegionServerStoppedException(".META. says the region "+
regionInfo.getRegionNameAsString()+" is managed by the server " + serverName +
", but it is dead.");
}

// Instantiate the location
location = new HRegionLocation(regionInfo, serverName,
HRegionInfo.getSeqNumDuringOpen(regionInfoRow));
cacheLocation(tableName, null, location);
return location;
} catch (TableNotFoundException e) {
// if we got this error, probably means the table just plain doesn't
// exist. rethrow the error immediately. this should always be coming
// from the HTable constructor.
throw e;
} catch (IOException e) {
if (e instanceof RemoteException) {
e = ((RemoteException)e).unwrapRemoteException();
}
if (tries < numTries - 1) {
if (LOG.isDebugEnabled()) {
LOG.debug("locateRegionInMeta parentTable=" +
parentTable + ", metaLocation=" +
((metaLocation == null)? "null": "{" + metaLocation + "}") +
", attempt=" + tries + " of " +
this.numTries + " failed; retrying after sleep of " +
ConnectionUtils.getPauseTime(this.pause, tries) + " because: " + e.getMessage());
}
} else {
throw e;
}
// Only relocate the parent region if necessary
if(!(e instanceof RegionOfflineException ||
e instanceof NoServerForRegionException)) {
relocateRegion(parentTable, metaKey);
}
}
try{
Thread.sleep(ConnectionUtils.getPauseTime(this.pause, tries));
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
throw new IOException("Giving up trying to location region in " +
"meta: thread is interrupted.");
}
}
}

/*
* Search the cache for a location that fits our table and row key.
* Return null if no suitable region is located. TODO: synchronization note
*
* <p>TODO: This method during writing consumes 15% of CPU doing lookup
* into the Soft Reference SortedMap.  Improve.
*
* @param tableName
* @param row
* @return Null or region location found in cache.
*/
HRegionLocation getCachedLocation(final TableName tableName,
final byte [] row) {
SoftValueSortedMap<byte[], HRegionLocation> tableLocations =
getTableLocations(tableName);

// start to examine the cache. we can only do cache actions
// if there's something in the cache for this table.
if (tableLocations.isEmpty()) {
return null;
}

HRegionLocation possibleRegion = tableLocations.get(row);
if (possibleRegion != null) {
return possibleRegion;
}

possibleRegion = tableLocations.lowerValueByKey(row);
if (possibleRegion == null) {
return null;
}

// make sure that the end key is greater than the row we're looking
// for, otherwise the row actually belongs in the next region, not
// this one. the exception case is when the endkey is
// HConstants.EMPTY_END_ROW, signifying that the region we're
// checking is actually the last region in the table.
byte[] endKey = possibleRegion.getRegionInfo().getEndKey();
if (Bytes.equals(endKey, HConstants.EMPTY_END_ROW) ||
KeyValue.getRowComparator(tableName).compareRows(
endKey, 0, endKey.length, row, 0, row.length) > 0) {
return possibleRegion;
}

// Passed all the way through, so we got nothing - complete cache miss
return null;
}

/**
* Delete a cached location, no matter what it is. Called when we were told to not use cache.
* @param tableName tableName
* @param row
*/
void forceDeleteCachedLocation(final TableName tableName, final byte [] row) {
HRegionLocation rl = null;
synchronized (this.cachedRegionLocations) {
Map<byte[], HRegionLocation> tableLocations = getTableLocations(tableName);
// start to examine the cache. we can only do cache actions
// if there's something in the cache for this table.
if (!tableLocations.isEmpty()) {
rl = getCachedLocation(tableName, row);
if (rl != null) {
tableLocations.remove(rl.getRegionInfo().getStartKey());
}
}
}
if ((rl != null) && LOG.isDebugEnabled()) {
LOG.debug("Removed " + rl.getHostname() + ":" + rl.getPort()
+ " as a location of " + rl.getRegionInfo().getRegionNameAsString() +
" for tableName=" + tableName + " from cache");
}
}

/*
* Delete all cached entries of a table that maps to a specific location.
*/
@Override
public void clearCaches(final ServerName serverName){
boolean deletedSomething = false;
synchronized (this.cachedRegionLocations) {
if (!cachedServers.contains(serverName)) {
return;
}
for (Map<byte[], HRegionLocation> tableLocations :
cachedRegionLocations.values()) {
for (Entry<byte[], HRegionLocation> e : tableLocations.entrySet()) {
HRegionLocation value = e.getValue();
if (value != null
&& serverName.equals(value.getServerName())) {
tableLocations.remove(e.getKey());
deletedSomething = true;
}
}
}
cachedServers.remove(serverName);
}
if (deletedSomething && LOG.isDebugEnabled()) {
LOG.debug("Removed all cached region locations that map to " + serverName);
}
}

/*
* @param tableName
* @return Map of cached locations for passed <code>tableName</code>
*/
private SoftValueSortedMap<byte[], HRegionLocation> getTableLocations(
final TableName tableName) {
// find the map of cached locations for this table
SoftValueSortedMap<byte[], HRegionLocation> result;
synchronized (this.cachedRegionLocations) {
result = this.cachedRegionLocations.get(tableName);
// if tableLocations for this table isn't built yet, make one
if (result == null) {
result = new SoftValueSortedMap<byte[], HRegionLocation>(Bytes.BYTES_COMPARATOR);
this.cachedRegionLocations.put(tableName, result);
}
}
return result;
}

@Override
public void clearRegionCache() {
synchronized(this.cachedRegionLocations) {
this.cachedRegionLocations.clear();
this.cachedServers.clear();
}
}

@Override
public void clearRegionCache(final TableName tableName) {
synchronized (this.cachedRegionLocations) {
this.cachedRegionLocations.remove(tableName);
}
}

@Override
public void clearRegionCache(final byte[] tableName) {
clearRegionCache(TableName.valueOf(tableName));
}

/**
* Put a newly discovered HRegionLocation into the cache.
* @param tableName The table name.
* @param source the source of the new location, if it's not coming from meta
* @param location the new location
*/
private void cacheLocation(final TableName tableName, final HRegionLocation source,
final HRegionLocation location) {
boolean isFromMeta = (source == null);
byte [] startKey = location.getRegionInfo().getStartKey();
Map<byte[], HRegionLocation> tableLocations =
getTableLocations(tableName);
boolean isNewCacheEntry = false;
boolean isStaleUpdate = false;
HRegionLocation oldLocation = null;
synchronized (this.cachedRegionLocations) {
cachedServers.add(location.getServerName());
oldLocation = tableLocations.get(startKey);
isNewCacheEntry = (oldLocation == null);
// If the server in cache sends us a redirect, assume it's always valid.
if (!isNewCacheEntry && !oldLocation.equals(source)) {
long newLocationSeqNum = location.getSeqNum();
// Meta record is stale - some (probably the same) server has closed the region
// with later seqNum and told us about the new location.
boolean isStaleMetaRecord = isFromMeta && (oldLocation.getSeqNum() > newLocationSeqNum);
// Same as above for redirect. However, in this case, if the number is equal to previous
// record, the most common case is that first the region was closed with seqNum, and then
// opened with the same seqNum; hence we will ignore the redirect.
// There are so many corner cases with various combinations of opens and closes that
// an additional counter on top of seqNum would be necessary to handle them all.
boolean isStaleRedirect = !isFromMeta && (oldLocation.getSeqNum() >= newLocationSeqNum);
isStaleUpdate = (isStaleMetaRecord || isStaleRedirect);
}
if (!isStaleUpdate) {
tableLocations.put(startKey, location);
}
}
if (isNewCacheEntry) {
if (LOG.isTraceEnabled()) {
LOG.trace("Cached location for " +
location.getRegionInfo().getRegionNameAsString() +
" is " + location.getHostnamePort());
}
} else if (isStaleUpdate && !location.equals(oldLocation)) {
if (LOG.isTraceEnabled()) {
LOG.trace("Ignoring stale location update for "
+ location.getRegionInfo().getRegionNameAsString() + ": "
+ location.getHostnamePort() + " at " + location.getSeqNum() + "; local "
+ oldLocation.getHostnamePort() + " at " + oldLocation.getSeqNum());
}
}
}

// Map keyed by service name + regionserver to service stub implementation
private final ConcurrentHashMap<String, Object> stubs =
new ConcurrentHashMap<String, Object>();
// Map of locks used creating service stubs per regionserver.
private final ConcurrentHashMap<String, String> connectionLock =
new ConcurrentHashMap<String, String>();

/**
* Maintains current state of MasterService instance.
*/
static abstract class MasterServiceState {
HConnection connection;
int userCount;
long keepAliveUntil = Long.MAX_VALUE;

MasterServiceState (final HConnection connection) {
super();
this.connection = connection;
}

abstract Object getStub();
abstract void clearStub();
abstract boolean isMasterRunning() throws ServiceException;
}

/**
* State of the MasterAdminService connection/setup.
*/
static class MasterAdminServiceState extends MasterServiceState {
MasterAdminService.BlockingInterface stub;
MasterAdminServiceState(final HConnection connection) {
super(connection);
}

@Override
public String toString() {
return "MasterAdminService";
}

@Override
Object getStub() {
return this.stub;
}

@Override
void clearStub() {
this.stub = null;
}

@Override
boolean isMasterRunning() throws ServiceException {
MasterProtos.IsMasterRunningResponse response =
this.stub.isMasterRunning(null, RequestConverter.buildIsMasterRunningRequest());
return response != null? response.getIsMasterRunning(): false;
}
}

/**
* State of the MasterMonitorService connection/setup.
*/
static class MasterMonitorServiceState extends MasterServiceState {
MasterMonitorService.BlockingInterface stub;
MasterMonitorServiceState(final HConnection connection) {
super(connection);
}

@Override
public String toString() {
return "MasterMonitorService";
}

@Override
Object getStub() {
return this.stub;
}

@Override
void clearStub() {
this.stub = null;
}

@Override
boolean isMasterRunning() throws ServiceException {
MasterProtos.IsMasterRunningResponse response =
this.stub.isMasterRunning(null, RequestConverter.buildIsMasterRunningRequest());
return response != null? response.getIsMasterRunning(): false;
}
}

/**
* Makes a client-side stub for master services. Sub-class to specialize.
* Depends on hosting class so not static.  Exists so we avoid duplicating a bunch of code
* when setting up the MasterMonitorService and MasterAdminService.
*/
abstract class StubMaker {
/**
* Returns the name of the service stub being created.
*/
protected abstract String getServiceName();

/**
* Make stub and cache it internal so can be used later doing the isMasterRunning call.
* @param channel
*/
protected abstract Object makeStub(final BlockingRpcChannel channel);

/**
* Once setup, check it works by doing isMasterRunning check.
* @throws ServiceException
*/
protected abstract void isMasterRunning() throws ServiceException;

/**
* Create a stub. Try once only.  It is not typed because there is no common type to
* protobuf services nor their interfaces.  Let the caller do appropriate casting.
* @return A stub for master services.
* @throws IOException
* @throws KeeperException
* @throws ServiceException
*/
private Object makeStubNoRetries() throws IOException, KeeperException, ServiceException {
ZooKeeperKeepAliveConnection zkw;
try {
zkw = getKeepAliveZooKeeperWatcher();
} catch (IOException e) {
throw new ZooKeeperConnectionException("Can't connect to ZooKeeper", e);
}
try {
checkIfBaseNodeAvailable(zkw);
ServerName sn = MasterAddressTracker.getMasterAddress(zkw);
if (sn == null) {
String msg = "ZooKeeper available but no active master location found";
LOG.info(msg);
throw new MasterNotRunningException(msg);
}
if (isDeadServer(sn)) {
throw new MasterNotRunningException(sn + " is dead.");
}
// Use the security info interface name as our stub key
String key = getStubKey(getServiceName(), sn.getHostAndPort());
connectionLock.putIfAbsent(key, key);
Object stub = null;
synchronized (connectionLock.get(key)) {
stub = stubs.get(key);
if (stub == null) {
BlockingRpcChannel channel = rpcClient.createBlockingRpcChannel(sn,
User.getCurrent(), rpcTimeout);
stub = makeStub(channel);
isMasterRunning();
stubs.put(key, stub);
}
}
return stub;
} finally {
zkw.close();
}
}

/**
* Create a stub against the master.  Retry if necessary.
* @return A stub to do <code>intf</code> against the master
* @throws MasterNotRunningException
*/
@edu.umd.cs.findbugs.annotations.SuppressWarnings (value="SWL_SLEEP_WITH_LOCK_HELD")
Object makeStub() throws MasterNotRunningException {
// The lock must be at the beginning to prevent multiple master creations
//  (and leaks) in a multithread context
synchronized (masterAndZKLock) {
Exception exceptionCaught = null;
Object stub = null;
int tries = 0;
while (!closed && stub == null) {
tries++;
try {
stub = makeStubNoRetries();
} catch (IOException e) {
exceptionCaught = e;
} catch (KeeperException e) {
exceptionCaught = e;
} catch (ServiceException e) {
exceptionCaught = e;
}

if (exceptionCaught != null)
// It failed. If it's not the last try, we're going to wait a little
if (tries < numTries) {
// tries at this point is 1 or more; decrement to start from 0.
long pauseTime = ConnectionUtils.getPauseTime(pause, tries - 1);
LOG.info("getMaster attempt " + tries + " of " + numTries +
" failed; retrying after sleep of " + pauseTime + ", exception=" +
exceptionCaught);

try {
Thread.sleep(pauseTime);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
throw new RuntimeException(
"Thread was interrupted while trying to connect to master.", e);
}
} else {
// Enough tries, we stop now
LOG.info("getMaster attempt " + tries + " of " + numTries +
" failed; no more retrying.", exceptionCaught);
throw new MasterNotRunningException(exceptionCaught);
}
}

if (stub == null) {
// implies this.closed true
throw new MasterNotRunningException("Connection was closed while trying to get master");
}
return stub;
}
}
}

/**
* Class to make a MasterMonitorService stub.
*/
class MasterMonitorServiceStubMaker extends StubMaker {
private MasterMonitorService.BlockingInterface stub;
@Override
protected String getServiceName() {
return MasterMonitorService.getDescriptor().getName();
}

@Override
@edu.umd.cs.findbugs.annotations.SuppressWarnings("SWL_SLEEP_WITH_LOCK_HELD")
MasterMonitorService.BlockingInterface makeStub() throws MasterNotRunningException {
return (MasterMonitorService.BlockingInterface)super.makeStub();
}

@Override
protected Object makeStub(BlockingRpcChannel channel) {
this.stub = MasterMonitorService.newBlockingStub(channel);
return this.stub;
}

@Override
protected void isMasterRunning() throws ServiceException {
this.stub.isMasterRunning(null, RequestConverter.buildIsMasterRunningRequest());
}
}

/**
* Class to make a MasterAdminService stub.
*/
class MasterAdminServiceStubMaker extends StubMaker {
private MasterAdminService.BlockingInterface stub;

@Override
protected String getServiceName() {
return MasterAdminService.getDescriptor().getName();
}

@Override
@edu.umd.cs.findbugs.annotations.SuppressWarnings("SWL_SLEEP_WITH_LOCK_HELD")
MasterAdminService.BlockingInterface makeStub() throws MasterNotRunningException {
return (MasterAdminService.BlockingInterface)super.makeStub();
}

@Override
protected Object makeStub(BlockingRpcChannel channel) {
this.stub = MasterAdminService.newBlockingStub(channel);
return this.stub;
}

@Override
protected void isMasterRunning() throws ServiceException {
this.stub.isMasterRunning(null, RequestConverter.buildIsMasterRunningRequest());
}
};

@Override
public AdminService.BlockingInterface getAdmin(final ServerName serverName)
throws IOException {
return getAdmin(serverName, false);
}

@Override
// Nothing is done w/ the 'master' parameter.  It is ignored.
public AdminService.BlockingInterface getAdmin(final ServerName serverName,
final boolean master)
throws IOException {
if (isDeadServer(serverName)) {
throw new RegionServerStoppedException(serverName + " is dead.");
}
String key = getStubKey(AdminService.BlockingInterface.class.getName(),
serverName.getHostAndPort());
this.connectionLock.putIfAbsent(key, key);
AdminService.BlockingInterface stub = null;
synchronized (this.connectionLock.get(key)) {
stub = (AdminService.BlockingInterface)this.stubs.get(key);
if (stub == null) {
BlockingRpcChannel channel = this.rpcClient.createBlockingRpcChannel(serverName,
User.getCurrent(), this.rpcTimeout);
stub = AdminService.newBlockingStub(channel);
this.stubs.put(key, stub);
}
}
return stub;
}

@Override
public ClientService.BlockingInterface getClient(final ServerName sn)
throws IOException {
if (isDeadServer(sn)) {
throw new RegionServerStoppedException(sn + " is dead.");
}
String key = getStubKey(ClientService.BlockingInterface.class.getName(), sn.getHostAndPort());
this.connectionLock.putIfAbsent(key, key);
ClientService.BlockingInterface stub = null;
synchronized (this.connectionLock.get(key)) {
stub = (ClientService.BlockingInterface)this.stubs.get(key);
if (stub == null) {
BlockingRpcChannel channel = this.rpcClient.createBlockingRpcChannel(sn,
User.getCurrent(), this.rpcTimeout);
stub = ClientService.newBlockingStub(channel);
// In old days, after getting stub/proxy, we'd make a call.  We are not doing that here.
// Just fail on first actual call rather than in here on setup.
this.stubs.put(key, stub);
}
}
return stub;
}

static String getStubKey(final String serviceName, final String rsHostnamePort) {
return serviceName + "@" + rsHostnamePort;
}

private ZooKeeperKeepAliveConnection keepAliveZookeeper;
private int keepAliveZookeeperUserCount;
private boolean canCloseZKW = true;

// keepAlive time, in ms. No reason to make it configurable.
private static final long keepAlive = 5 * 60 * 1000;

/**
* Retrieve a shared ZooKeeperWatcher. You must close it it once you've have finished with it.
* @return The shared instance. Never returns null.
*/
ZooKeeperKeepAliveConnection getKeepAliveZooKeeperWatcher()
throws IOException {
synchronized (masterAndZKLock) {
if (keepAliveZookeeper == null) {
if (this.closed) {
throw new IOException(toString() + " closed");
}
// We don't check that our link to ZooKeeper is still valid
// But there is a retry mechanism in the ZooKeeperWatcher itself
keepAliveZookeeper = new ZooKeeperKeepAliveConnection(conf, this.toString(), this);
}
keepAliveZookeeperUserCount++;
keepZooKeeperWatcherAliveUntil = Long.MAX_VALUE;
return keepAliveZookeeper;
}
}

void releaseZooKeeperWatcher(final ZooKeeperWatcher zkw) {
if (zkw == null){
return;
}
synchronized (masterAndZKLock) {
--keepAliveZookeeperUserCount;
if (keepAliveZookeeperUserCount <= 0 ){
keepZooKeeperWatcherAliveUntil = System.currentTimeMillis() + keepAlive;
}
}
}

/**
* Creates a Chore thread to check the connections to master & zookeeper
*  and close them when they reach their closing time (
*  {@link MasterServiceState#keepAliveUntil} and
*  {@link #keepZooKeeperWatcherAliveUntil}). Keep alive time is
*  managed by the release functions and the variable {@link #keepAlive}
*/
private static class DelayedClosing extends Chore implements Stoppable {
private HConnectionImplementation hci;
Stoppable stoppable;

private DelayedClosing(
HConnectionImplementation hci, Stoppable stoppable){
super(
"ZooKeeperWatcher and Master delayed closing for connection "+hci,
60*1000, // We check every minutes
stoppable);
this.hci = hci;
this.stoppable = stoppable;
}

static DelayedClosing createAndStart(HConnectionImplementation hci){
Stoppable stoppable = new Stoppable() {
private volatile boolean isStopped = false;
@Override public void stop(String why) { isStopped = true;}
@Override public boolean isStopped() {return isStopped;}
};

return new DelayedClosing(hci, stoppable);
}

protected void closeMasterProtocol(MasterServiceState protocolState) {
if (System.currentTimeMillis() > protocolState.keepAliveUntil) {
hci.closeMasterService(protocolState);
protocolState.keepAliveUntil = Long.MAX_VALUE;
}
}

@Override
protected void chore() {
synchronized (hci.masterAndZKLock) {
if (hci.canCloseZKW) {
if (System.currentTimeMillis() >
hci.keepZooKeeperWatcherAliveUntil) {

hci.closeZooKeeperWatcher();
hci.keepZooKeeperWatcherAliveUntil = Long.MAX_VALUE;
}
}
closeMasterProtocol(hci.adminMasterServiceState);
closeMasterProtocol(hci.monitorMasterServiceState);
}
}

@Override
public void stop(String why) {
stoppable.stop(why);
}

@Override
public boolean isStopped() {
return stoppable.isStopped();
}
}

private void closeZooKeeperWatcher() {
synchronized (masterAndZKLock) {
if (keepAliveZookeeper != null) {
LOG.info("Closing zookeeper sessionid=0x" +
Long.toHexString(
keepAliveZookeeper.getRecoverableZooKeeper().getSessionId()));
keepAliveZookeeper.internalClose();
keepAliveZookeeper = null;
}
keepAliveZookeeperUserCount = 0;
}
}

final MasterAdminServiceState adminMasterServiceState = new MasterAdminServiceState(this);
final MasterMonitorServiceState monitorMasterServiceState =
new MasterMonitorServiceState(this);

@Override
public MasterAdminService.BlockingInterface getMasterAdmin() throws MasterNotRunningException {
return getKeepAliveMasterAdminService();
}

@Override
public MasterMonitorService.BlockingInterface getMasterMonitor()
throws MasterNotRunningException {
return getKeepAliveMasterMonitorService();
}

private void resetMasterServiceState(final MasterServiceState mss) {
mss.userCount++;
mss.keepAliveUntil = Long.MAX_VALUE;
}

@Override
public MasterAdminKeepAliveConnection getKeepAliveMasterAdminService()
throws MasterNotRunningException {
synchronized (masterAndZKLock) {
if (!isKeepAliveMasterConnectedAndRunning(this.adminMasterServiceState)) {
MasterAdminServiceStubMaker stubMaker = new MasterAdminServiceStubMaker();
this.adminMasterServiceState.stub = stubMaker.makeStub();
}
resetMasterServiceState(this.adminMasterServiceState);
}
// Ugly delegation just so we can add in a Close method.
final MasterAdminService.BlockingInterface stub = this.adminMasterServiceState.stub;
return new MasterAdminKeepAliveConnection() {
MasterAdminServiceState mss = adminMasterServiceState;
@Override
public AddColumnResponse addColumn(RpcController controller,
AddColumnRequest request) throws ServiceException {
return stub.addColumn(controller, request);
}

@Override
public DeleteColumnResponse deleteColumn(RpcController controller,
DeleteColumnRequest request) throws ServiceException {
return stub.deleteColumn(controller, request);
}

@Override
public ModifyColumnResponse modifyColumn(RpcController controller,
ModifyColumnRequest request) throws ServiceException {
return stub.modifyColumn(controller, request);
}

@Override
public MoveRegionResponse moveRegion(RpcController controller,
MoveRegionRequest request) throws ServiceException {
return stub.moveRegion(controller, request);
}

@Override
public DispatchMergingRegionsResponse dispatchMergingRegions(
RpcController controller, DispatchMergingRegionsRequest request)
throws ServiceException {
return stub.dispatchMergingRegions(controller, request);
}

@Override
public AssignRegionResponse assignRegion(RpcController controller,
AssignRegionRequest request) throws ServiceException {
return stub.assignRegion(controller, request);
}

@Override
public UnassignRegionResponse unassignRegion(RpcController controller,
UnassignRegionRequest request) throws ServiceException {
return stub.unassignRegion(controller, request);
}

@Override
public OfflineRegionResponse offlineRegion(RpcController controller,
OfflineRegionRequest request) throws ServiceException {
return stub.offlineRegion(controller, request);
}

@Override
public DeleteTableResponse deleteTable(RpcController controller,
DeleteTableRequest request) throws ServiceException {
return stub.deleteTable(controller, request);
}

@Override
public EnableTableResponse enableTable(RpcController controller,
EnableTableRequest request) throws ServiceException {
return stub.enableTable(controller, request);
}

@Override
public DisableTableResponse disableTable(RpcController controller,
DisableTableRequest request) throws ServiceException {
return stub.disableTable(controller, request);
}

@Override
public ModifyTableResponse modifyTable(RpcController controller,
ModifyTableRequest request) throws ServiceException {
return stub.modifyTable(controller, request);
}

@Override
public CreateTableResponse createTable(RpcController controller,
CreateTableRequest request) throws ServiceException {
return stub.createTable(controller, request);
}

@Override
public ShutdownResponse shutdown(RpcController controller,
ShutdownRequest request) throws ServiceException {
return stub.shutdown(controller, request);
}

@Override
public StopMasterResponse stopMaster(RpcController controller,
StopMasterRequest request) throws ServiceException {
return stub.stopMaster(controller, request);
}

@Override
public BalanceResponse balance(RpcController controller,
BalanceRequest request) throws ServiceException {
return stub.balance(controller, request);
}

@Override
public SetBalancerRunningResponse setBalancerRunning(
RpcController controller, SetBalancerRunningRequest request)
throws ServiceException {
return stub.setBalancerRunning(controller, request);
}

@Override
public CatalogScanResponse runCatalogScan(RpcController controller,
CatalogScanRequest request) throws ServiceException {
return stub.runCatalogScan(controller, request);
}

@Override
public EnableCatalogJanitorResponse enableCatalogJanitor(
RpcController controller, EnableCatalogJanitorRequest request)
throws ServiceException {
return stub.enableCatalogJanitor(controller, request);
}

@Override
public IsCatalogJanitorEnabledResponse isCatalogJanitorEnabled(
RpcController controller, IsCatalogJanitorEnabledRequest request)
throws ServiceException {
return stub.isCatalogJanitorEnabled(controller, request);
}

@Override
public CoprocessorServiceResponse execMasterService(
RpcController controller, CoprocessorServiceRequest request)
throws ServiceException {
return stub.execMasterService(controller, request);
}

@Override
public TakeSnapshotResponse snapshot(RpcController controller,
TakeSnapshotRequest request) throws ServiceException {
return stub.snapshot(controller, request);
}

@Override
public ListSnapshotResponse getCompletedSnapshots(
RpcController controller, ListSnapshotRequest request)
throws ServiceException {
return stub.getCompletedSnapshots(controller, request);
}

@Override
public DeleteSnapshotResponse deleteSnapshot(RpcController controller,
DeleteSnapshotRequest request) throws ServiceException {
return stub.deleteSnapshot(controller, request);
}

@Override
public IsSnapshotDoneResponse isSnapshotDone(RpcController controller,
IsSnapshotDoneRequest request) throws ServiceException {
return stub.isSnapshotDone(controller, request);
}

@Override
public RestoreSnapshotResponse restoreSnapshot(
RpcController controller, RestoreSnapshotRequest request)
throws ServiceException {
return stub.restoreSnapshot(controller, request);
}

@Override
public IsRestoreSnapshotDoneResponse isRestoreSnapshotDone(
RpcController controller, IsRestoreSnapshotDoneRequest request)
throws ServiceException {
return stub.isRestoreSnapshotDone(controller, request);
}

@Override
public IsMasterRunningResponse isMasterRunning(
RpcController controller, IsMasterRunningRequest request)
throws ServiceException {
return stub.isMasterRunning(controller, request);
}

@Override
public ModifyNamespaceResponse modifyNamespace(RpcController controller, ModifyNamespaceRequest request) throws ServiceException {
return stub.modifyNamespace(controller, request);
}

@Override
public CreateNamespaceResponse createNamespace(RpcController controller, CreateNamespaceRequest request) throws ServiceException {
return stub.createNamespace(controller, request);
}

@Override
public DeleteNamespaceResponse deleteNamespace(RpcController controller, DeleteNamespaceRequest request) throws ServiceException {
return stub.deleteNamespace(controller, request);
}

@Override
public GetNamespaceDescriptorResponse getNamespaceDescriptor(RpcController controller, GetNamespaceDescriptorRequest request) throws ServiceException {
return stub.getNamespaceDescriptor(controller, request);
}

@Override
public ListNamespaceDescriptorsResponse listNamespaceDescriptors(RpcController controller, ListNamespaceDescriptorsRequest request) throws ServiceException {
return stub.listNamespaceDescriptors(controller, request);
}

@Override
public ListTableDescriptorsByNamespaceResponse listTableDescriptorsByNamespace(RpcController controller, ListTableDescriptorsByNamespaceRequest request) throws ServiceException {
return stub.listTableDescriptorsByNamespace(controller, request);
}

@Override
public ListTableNamesByNamespaceResponse listTableNamesByNamespace(RpcController controller,
ListTableNamesByNamespaceRequest request) throws ServiceException {
return stub.listTableNamesByNamespace(controller, request);
}

@Override
public void close() {
release(this.mss);
}
};
}

private static void release(MasterServiceState mss) {
if (mss != null && mss.connection != null) {
((HConnectionImplementation)mss.connection).releaseMaster(mss);
}
}

@Override
public MasterMonitorKeepAliveConnection getKeepAliveMasterMonitorService()
throws MasterNotRunningException {
synchronized (masterAndZKLock) {
if (!isKeepAliveMasterConnectedAndRunning(this.monitorMasterServiceState)) {
MasterMonitorServiceStubMaker stubMaker = new MasterMonitorServiceStubMaker();
this.monitorMasterServiceState.stub = stubMaker.makeStub();
}
resetMasterServiceState(this.monitorMasterServiceState);
}
// Ugly delegation just so can implement close
final MasterMonitorService.BlockingInterface stub = this.monitorMasterServiceState.stub;
return new MasterMonitorKeepAliveConnection() {
final MasterMonitorServiceState mss = monitorMasterServiceState;
@Override
public GetSchemaAlterStatusResponse getSchemaAlterStatus(
RpcController controller, GetSchemaAlterStatusRequest request)
throws ServiceException {
return stub.getSchemaAlterStatus(controller, request);
}

@Override
public GetTableDescriptorsResponse getTableDescriptors(
RpcController controller, GetTableDescriptorsRequest request)
throws ServiceException {
return stub.getTableDescriptors(controller, request);
}

@Override
public GetTableNamesResponse getTableNames(
RpcController controller, GetTableNamesRequest request)
throws ServiceException {
return stub.getTableNames(controller, request);
}

@Override
public GetClusterStatusResponse getClusterStatus(
RpcController controller, GetClusterStatusRequest request)
throws ServiceException {
return stub.getClusterStatus(controller, request);
}

@Override
public IsMasterRunningResponse isMasterRunning(
RpcController controller, IsMasterRunningRequest request)
throws ServiceException {
return stub.isMasterRunning(controller, request);
}

@Override
public void close() throws IOException {
release(this.mss);
}
};
}

private boolean isKeepAliveMasterConnectedAndRunning(MasterServiceState mss) {
if (mss.getStub() == null){
return false;
}
try {
return mss.isMasterRunning();
} catch (UndeclaredThrowableException e) {
// It's somehow messy, but we can receive exceptions such as
//  java.net.ConnectException but they're not declared. So we catch it...
LOG.info("Master connection is not running anymore", e.getUndeclaredThrowable());
return false;
} catch (ServiceException se) {
LOG.warn("Checking master connection", se);
return false;
}
}

void releaseMaster(MasterServiceState mss) {
if (mss.getStub() == null) return;
synchronized (masterAndZKLock) {
--mss.userCount;
if (mss.userCount <= 0) {
mss.keepAliveUntil = System.currentTimeMillis() + keepAlive;
}
}
}

private void closeMasterService(MasterServiceState mss) {
if (mss.getStub() != null) {
LOG.info("Closing master protocol: " + mss);
mss.clearStub();
}
mss.userCount = 0;
}

/**
* Immediate close of the shared master. Can be by the delayed close or when closing the
* connection itself.
*/
private void closeMaster() {
synchronized (masterAndZKLock) {
closeMasterService(adminMasterServiceState);
closeMasterService(monitorMasterServiceState);
}
}

void updateCachedLocation(HRegionInfo hri, HRegionLocation source,
ServerName serverName, long seqNum) {
HRegionLocation newHrl = new HRegionLocation(hri, serverName, seqNum);
synchronized (this.cachedRegionLocations) {
cacheLocation(hri.getTableName(), source, newHrl);
}
}

/**
* Deletes the cached location of the region if necessary, based on some error from source.
* @param hri The region in question.
* @param source The source of the error that prompts us to invalidate cache.
*/
void deleteCachedLocation(HRegionInfo hri, HRegionLocation source) {
boolean isStaleDelete = false;
HRegionLocation oldLocation;
synchronized (this.cachedRegionLocations) {
Map<byte[], HRegionLocation> tableLocations =
getTableLocations(hri.getTableName());
oldLocation = tableLocations.get(hri.getStartKey());
if (oldLocation != null) {
// Do not delete the cache entry if it's not for the same server that gave us the error.
isStaleDelete = (source != null) && !oldLocation.equals(source);
if (!isStaleDelete) {
tableLocations.remove(hri.getStartKey());
}
}
}
}

@Override
public void deleteCachedRegionLocation(final HRegionLocation location) {
if (location == null) {
return;
}
synchronized (this.cachedRegionLocations) {
TableName tableName = location.getRegionInfo().getTableName();
Map<byte[], HRegionLocation> tableLocations =
getTableLocations(tableName);
if (!tableLocations.isEmpty()) {
// Delete if there's something in the cache for this region.
HRegionLocation removedLocation =
tableLocations.remove(location.getRegionInfo().getStartKey());
if (LOG.isDebugEnabled() && removedLocation != null) {
LOG.debug("Removed " +
location.getRegionInfo().getRegionNameAsString() +
" for tableName=" + tableName +
" from cache");
}
}
}
}

/**
* Update the location with the new value (if the exception is a RegionMovedException)
* or delete it from the cache.
* @param exception an object (to simplify user code) on which we will try to find a nested
*                  or wrapped or both RegionMovedException
* @param source server that is the source of the location update.
*/
@Override
public void updateCachedLocations(final TableName tableName, byte[] rowkey,
final Object exception, final HRegionLocation source) {
if (rowkey == null || tableName == null) {
LOG.warn("Coding error, see method javadoc. row=" + (rowkey == null ? "null" : rowkey) +
", tableName=" + (tableName == null ? "null" : tableName));
return;
}

// Is it something we have already updated?
final HRegionLocation oldLocation = getCachedLocation(tableName, rowkey);
if (oldLocation == null) {
// There is no such location in the cache => it's been removed already => nothing to do
return;
}

HRegionInfo regionInfo = oldLocation.getRegionInfo();
final RegionMovedException rme = RegionMovedException.find(exception);
if (rme != null) {
if (LOG.isTraceEnabled()){
LOG.trace("Region " + regionInfo.getRegionNameAsString() + " moved to " +
rme.getHostname() + ":" + rme.getPort() + " according to " + source.getHostnamePort());
}
updateCachedLocation(
regionInfo, source, rme.getServerName(), rme.getLocationSeqNum());
} else if (RegionOpeningException.find(exception) != null) {
if (LOG.isTraceEnabled()) {
LOG.trace("Region " + regionInfo.getRegionNameAsString() + " is being opened on "
+ source.getHostnamePort() + "; not deleting the cache entry");
}
} else {
deleteCachedLocation(regionInfo, source);
}
}

@Override
public void updateCachedLocations(final byte[] tableName, byte[] rowkey,
final Object exception, final HRegionLocation source) {
updateCachedLocations(TableName.valueOf(tableName), rowkey, exception, source);
}

@Override
@Deprecated
public void processBatch(List<? extends Row> list,
final TableName tableName,
ExecutorService pool,
Object[] results) throws IOException, InterruptedException {
// This belongs in HTable!!! Not in here.  St.Ack

// results must be the same size as list
if (results.length != list.size()) {
throw new IllegalArgumentException(
"argument results must be the same size as argument list");
}
processBatchCallback(list, tableName, pool, results, null);
}

@Override
@Deprecated
public void processBatch(List<? extends Row> list,
final byte[] tableName,
ExecutorService pool,
Object[] results) throws IOException, InterruptedException {
processBatch(list, TableName.valueOf(tableName), pool, results);
}

/**
* Send the queries in parallel on the different region servers. Retries on failures.
* If the method returns it means that there is no error, and the 'results' array will
* contain no exception. On error, an exception is thrown, and the 'results' array will
* contain results and exceptions.
* @deprecated since 0.96 - Use {@link HTable#processBatchCallback} instead
*/
@Override
@Deprecated
public <R> void processBatchCallback(
List<? extends Row> list,
TableName tableName,
ExecutorService pool,
Object[] results,
Batch.Callback<R> callback)
throws IOException, InterruptedException {

// To fulfill the original contract, we have a special callback. This callback
//  will set the results in the Object array.
ObjectResultFiller<R> cb = new ObjectResultFiller<R>(results, callback);
AsyncProcess<?> asyncProcess = createAsyncProcess(tableName, pool, cb, conf);

// We're doing a submit all. This way, the originalIndex will match the initial list.
asyncProcess.submitAll(list);
asyncProcess.waitUntilDone();

if (asyncProcess.hasError()) {
throw asyncProcess.getErrors();
}
}

@Override
@Deprecated
public <R> void processBatchCallback(
List<? extends Row> list,
byte[] tableName,
ExecutorService pool,
Object[] results,
Batch.Callback<R> callback)
throws IOException, InterruptedException {
processBatchCallback(list, TableName.valueOf(tableName), pool, results, callback);
}

// For tests.
protected <R> AsyncProcess createAsyncProcess(TableName tableName, ExecutorService pool,
AsyncProcess.AsyncProcessCallback<R> callback, Configuration conf) {
return new AsyncProcess<R>(this, tableName, pool, callback, conf,
RpcRetryingCallerFactory.instantiate(conf));
}


/**
* Fill the result array for the interfaces using it.
*/
private static class ObjectResultFiller<Res>
implements AsyncProcess.AsyncProcessCallback<Res> {

private final Object[] results;
private Batch.Callback<Res> callback;

ObjectResultFiller(Object[] results, Batch.Callback<Res> callback) {
this.results = results;
this.callback = callback;
}

@Override
public void success(int pos, byte[] region, Row row, Res result) {
assert pos < results.length;
results[pos] = result;
if (callback != null) {
callback.update(region, row.getRow(), result);
}
}

@Override
public boolean failure(int pos, byte[] region, Row row, Throwable t) {
assert pos < results.length;
results[pos] = t;
//Batch.Callback<Res> was not called on failure in 0.94. We keep this.
return true; // we want to have this failure in the failures list.
}

@Override
public boolean retriableFailure(int originalIndex, Row row, byte[] region,
Throwable exception) {
return true; // we retry
}
}

/*
* Return the number of cached region for a table. It will only be called
* from a unit test.
*/
int getNumberOfCachedRegionLocations(final TableName tableName) {
synchronized (this.cachedRegionLocations) {
Map<byte[], HRegionLocation> tableLocs = this.cachedRegionLocations.get(tableName);
if (tableLocs == null) {
return 0;
}
return tableLocs.values().size();
}
}

/**
* Check the region cache to see whether a region is cached yet or not.
* Called by unit tests.
* @param tableName tableName
* @param row row
* @return Region cached or not.
*/
boolean isRegionCached(TableName tableName, final byte[] row) {
HRegionLocation location = getCachedLocation(tableName, row);
return location != null;
}

@Override
public void setRegionCachePrefetch(final TableName tableName,
final boolean enable) {
if (!enable) {
regionCachePrefetchDisabledTables.add(Bytes.mapKey(tableName.getName()));
}
else {
regionCachePrefetchDisabledTables.remove(Bytes.mapKey(tableName.getName()));
}
}

@Override
public void setRegionCachePrefetch(final byte[] tableName,
final boolean enable) {
setRegionCachePrefetch(TableName.valueOf(tableName), enable);
}

@Override
public boolean getRegionCachePrefetch(TableName tableName) {
return !regionCachePrefetchDisabledTables.contains(Bytes.mapKey(tableName.getName()));
}

@Override
public boolean getRegionCachePrefetch(byte[] tableName) {
return getRegionCachePrefetch(TableName.valueOf(tableName));
}

@Override
public void abort(final String msg, Throwable t) {
if (t instanceof KeeperException.SessionExpiredException
&& keepAliveZookeeper != null) {
synchronized (masterAndZKLock) {
if (keepAliveZookeeper != null) {
LOG.warn("This client just lost it's session with ZooKeeper," +
" closing it." +
" It will be recreated next time someone needs it", t);
closeZooKeeperWatcher();
}
}
} else {
if (t != null) {
LOG.fatal(msg, t);
} else {
LOG.fatal(msg);
}
this.aborted = true;
close();
this.closed = true;
}
}

@Override
public boolean isClosed() {
return this.closed;
}

@Override
public boolean isAborted(){
return this.aborted;
}

@Override
public int getCurrentNrHRS() throws IOException {
return this.registry.getCurrentNrHRS();
}

/**
* Increment this client's reference count.
*/
void incCount() {
++refCount;
}

/**
* Decrement this client's reference count.
*/
void decCount() {
if (refCount > 0) {
--refCount;
}
}

/**
* Return if this client has no reference
*
* @return true if this client has no reference; false otherwise
*/
boolean isZeroReference() {
return refCount == 0;
}

void internalClose() {
if (this.closed) {
return;
}
delayedClosing.stop("Closing connection");
closeMaster();
shutdownBatchPool();
this.closed = true;
closeZooKeeperWatcher();
this.stubs.clear();
if (clusterStatusListener != null) {
clusterStatusListener.close();
}
}

@Override
public void close() {
if (managed) {
if (aborted) {
HConnectionManager.deleteStaleConnection(this);
} else {
HConnectionManager.deleteConnection(this, false);
}
} else {
internalClose();
}
}

/**
* Close the connection for good, regardless of what the current value of
* {@link #refCount} is. Ideally, {@link #refCount} should be zero at this
* point, which would be the case if all of its consumers close the
* connection. However, on the off chance that someone is unable to close
* the connection, perhaps because it bailed out prematurely, the method
* below will ensure that this {@link HConnection} instance is cleaned up.
* Caveat: The JVM may take an unknown amount of time to call finalize on an
* unreachable object, so our hope is that every consumer cleans up after
* itself, like any good citizen.
*/
@Override
protected void finalize() throws Throwable {
super.finalize();
// Pretend as if we are about to release the last remaining reference
refCount = 1;
close();
}

@Override
public HTableDescriptor[] listTables() throws IOException {
MasterMonitorKeepAliveConnection master = getKeepAliveMasterMonitorService();
try {
GetTableDescriptorsRequest req =
RequestConverter.buildGetTableDescriptorsRequest((List<TableName>)null);
return ProtobufUtil.getHTableDescriptorArray(master.getTableDescriptors(null, req));
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
}

@Override
public String[] getTableNames() throws IOException {
TableName[] tableNames = listTableNames();
String result[] = new String[tableNames.length];
for (int i = 0; i < tableNames.length; i++) {
result[i] = tableNames[i].getNameAsString();
}
return result;
}

@Override
public TableName[] listTableNames() throws IOException {
MasterMonitorKeepAliveConnection master = getKeepAliveMasterMonitorService();
try {
return ProtobufUtil.getTableNameArray(master.getTableNames(null,
GetTableNamesRequest.newBuilder().build())
.getTableNamesList());
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
}

@Override
public HTableDescriptor[] getHTableDescriptorsByTableName(
List<TableName> tableNames) throws IOException {
if (tableNames == null || tableNames.isEmpty()) return new HTableDescriptor[0];
MasterMonitorKeepAliveConnection master = getKeepAliveMasterMonitorService();
try {
GetTableDescriptorsRequest req =
RequestConverter.buildGetTableDescriptorsRequest(tableNames);
return ProtobufUtil.getHTableDescriptorArray(master.getTableDescriptors(null, req));
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
}

@Override
public HTableDescriptor[] getHTableDescriptors(
List<String> names) throws IOException {
List<TableName> tableNames = new ArrayList(names.size());
for(String name : names) {
tableNames.add(TableName.valueOf(name));
}

return getHTableDescriptorsByTableName(tableNames);
}

/**
* Connects to the master to get the table descriptor.
* @param tableName table name
* @return
* @throws IOException if the connection to master fails or if the table
*  is not found.
*/
@Override
public HTableDescriptor getHTableDescriptor(final TableName tableName)
throws IOException {
if (tableName == null) return null;
if (tableName.equals(TableName.META_TABLE_NAME)) {
return HTableDescriptor.META_TABLEDESC;
}
MasterMonitorKeepAliveConnection master = getKeepAliveMasterMonitorService();
GetTableDescriptorsResponse htds;
try {
GetTableDescriptorsRequest req =
RequestConverter.buildGetTableDescriptorsRequest(tableName);
htds = master.getTableDescriptors(null, req);
} catch (ServiceException se) {
throw ProtobufUtil.getRemoteException(se);
} finally {
master.close();
}
if (!htds.getTableSchemaList().isEmpty()) {
return HTableDescriptor.convert(htds.getTableSchemaList().get(0));
}
throw new TableNotFoundException(tableName.getNameAsString());
}

@Override
public HTableDescriptor getHTableDescriptor(final byte[] tableName)
throws IOException {
return getHTableDescriptor(TableName.valueOf(tableName));
}
}

/**
* The record of errors for servers.
*/
static class ServerErrorTracker {
// We need a concurrent map here, as we could have multiple threads updating it in parallel.
private final ConcurrentMap<HRegionLocation, ServerErrors> errorsByServer =
new ConcurrentHashMap<HRegionLocation, ServerErrors>();
private long canRetryUntil = 0;

public ServerErrorTracker(long timeout) {
LOG.trace("Server tracker timeout is " + timeout + "ms");
this.canRetryUntil = EnvironmentEdgeManager.currentTimeMillis() + timeout;
}

boolean canRetryMore() {
return EnvironmentEdgeManager.currentTimeMillis() < this.canRetryUntil;
}

/**
* Calculates the back-off time for a retrying request to a particular server.
*
* @param server    The server in question.
* @param basePause The default hci pause.
* @return The time to wait before sending next request.
*/
long calculateBackoffTime(HRegionLocation server, long basePause) {
long result = 0;
ServerErrors errorStats = errorsByServer.get(server);
if (errorStats != null) {
result = ConnectionUtils.getPauseTime(basePause, errorStats.retries);
// Adjust by the time we already waited since last talking to this server.
long now = EnvironmentEdgeManager.currentTimeMillis();
long timeSinceLastError = now - errorStats.getLastErrorTime();
if (timeSinceLastError > 0) {
result = Math.max(0, result - timeSinceLastError);
}
// Finally, see if the backoff time overshoots the timeout.
if (result > 0 && (now + result > this.canRetryUntil)) {
result = Math.max(0, this.canRetryUntil - now);
}
}
return result;
}

/**
* Reports that there was an error on the server to do whatever bean-counting necessary.
*
* @param server The server in question.
*/
void reportServerError(HRegionLocation server) {
ServerErrors errors = errorsByServer.get(server);
if (errors != null) {
errors.addError();
} else {
errorsByServer.put(server, new ServerErrors());
}
}

/**
* The record of errors for a server.
*/
private static class ServerErrors {
public long lastErrorTime;
public int retries;

public ServerErrors() {
this.lastErrorTime = EnvironmentEdgeManager.currentTimeMillis();
this.retries = 0;
}

public void addError() {
this.lastErrorTime = EnvironmentEdgeManager.currentTimeMillis();
++this.retries;
}

public long getLastErrorTime() {
return this.lastErrorTime;
}
}
}

/**
* Set the number of retries to use serverside when trying to communicate
* with another server over {@link HConnection}.  Used updating catalog
* tables, etc.  Call this method before we create any Connections.
* @param c The Configuration instance to set the retries into.
* @param log Used to log what we set in here.
*/
public static void setServerSideHConnectionRetries(final Configuration c, final String sn,
final Log log) {
int hcRetries = c.getInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
HConstants.DEFAULT_HBASE_CLIENT_RETRIES_NUMBER);
// Go big.  Multiply by 10.  If we can't get to meta after this many retries
// then something seriously wrong.
int serversideMultiplier = c.getInt("hbase.client.serverside.retries.multiplier", 10);
int retries = hcRetries * serversideMultiplier;
c.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, retries);
log.debug(sn + " HConnection server-to-server retries=" + retries);
}
}