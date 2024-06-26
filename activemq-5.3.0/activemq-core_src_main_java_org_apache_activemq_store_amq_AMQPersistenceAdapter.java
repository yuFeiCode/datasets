/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.activemq.store.amq;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activeio.journal.Journal;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerServiceAware;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.DataStructure;
import org.apache.activemq.command.JournalQueueAck;
import org.apache.activemq.command.JournalTopicAck;
import org.apache.activemq.command.JournalTrace;
import org.apache.activemq.command.JournalTransaction;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.filter.NonCachedMessageEvaluationContext;
import org.apache.activemq.kaha.impl.async.AsyncDataManager;
import org.apache.activemq.kaha.impl.async.Location;
import org.apache.activemq.kaha.impl.index.hash.HashIndex;
import org.apache.activemq.openwire.OpenWireFormat;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.ReferenceStore;
import org.apache.activemq.store.ReferenceStoreAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.TopicReferenceStore;
import org.apache.activemq.store.TransactionStore;
import org.apache.activemq.store.kahadaptor.KahaReferenceStoreAdapter;
import org.apache.activemq.thread.DefaultThreadPools;
import org.apache.activemq.thread.Scheduler;
import org.apache.activemq.thread.Task;
import org.apache.activemq.thread.TaskRunner;
import org.apache.activemq.thread.TaskRunnerFactory;
import org.apache.activemq.usage.SystemUsage;
import org.apache.activemq.usage.Usage;
import org.apache.activemq.usage.UsageListener;
import org.apache.activemq.util.ByteSequence;
import org.apache.activemq.util.IOExceptionSupport;
import org.apache.activemq.util.IOHelper;
import org.apache.activemq.wireformat.WireFormat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
* An implementation of {@link PersistenceAdapter} designed for use with a
* {@link Journal} and then check pointing asynchronously on a timeout with some
* other long term persistent storage.
*
* @org.apache.xbean.XBean element="amqPersistenceAdapter"
* @version $Revision: 1.17 $
*/
public class AMQPersistenceAdapter implements PersistenceAdapter, UsageListener, BrokerServiceAware {

private static final Log LOG = LogFactory.getLog(AMQPersistenceAdapter.class);
private static final Scheduler scheduler = Scheduler.getInstance();
private final ConcurrentHashMap<ActiveMQQueue, AMQMessageStore> queues = new ConcurrentHashMap<ActiveMQQueue, AMQMessageStore>();
private final ConcurrentHashMap<ActiveMQTopic, AMQTopicMessageStore> topics = new ConcurrentHashMap<ActiveMQTopic, AMQTopicMessageStore>();
private static final String PROPERTY_PREFIX = "org.apache.activemq.store.amq";
private static final boolean BROKEN_FILE_LOCK;
private static final boolean DISABLE_LOCKING;
private static final int JOURNAL_LOCKED_WAIT_DELAY = 10 * 1000;
private AsyncDataManager asyncDataManager;
private ReferenceStoreAdapter referenceStoreAdapter;
private TaskRunnerFactory taskRunnerFactory;
private WireFormat wireFormat = new OpenWireFormat();
private SystemUsage usageManager;
private long checkpointInterval = 1000 * 20;
private int maxCheckpointMessageAddSize = 1024 * 4;
private AMQTransactionStore transactionStore = new AMQTransactionStore(this);
private TaskRunner checkpointTask;
private CountDownLatch nextCheckpointCountDownLatch = new CountDownLatch(1);
private final AtomicBoolean started = new AtomicBoolean(false);
private Runnable periodicCheckpointTask;
private Runnable periodicCleanupTask;
private boolean deleteAllMessages;
private boolean syncOnWrite;
private boolean syncOnTransaction=true;
private String brokerName = "";
private File directory;
private File directoryArchive;
private BrokerService brokerService;
private AtomicLong storeSize = new AtomicLong();
private boolean persistentIndex=true;
private boolean useNio = true;
private boolean archiveDataLogs=false;
private long cleanupInterval = AsyncDataManager.DEFAULT_CLEANUP_INTERVAL;
private int maxFileLength = AsyncDataManager.DEFAULT_MAX_FILE_LENGTH;
private int indexBinSize = HashIndex.DEFAULT_BIN_SIZE;
private int indexKeySize = HashIndex.DEFAULT_KEY_SIZE;
private int indexPageSize = HashIndex.DEFAULT_PAGE_SIZE;
private int indexMaxBinSize = HashIndex.MAXIMUM_CAPACITY;
private int indexLoadFactor = HashIndex.DEFAULT_LOAD_FACTOR;
private int maxReferenceFileLength=AMQPersistenceAdapterFactory.DEFAULT_MAX_REFERNCE_FILE_LENGTH;
private Map<AMQMessageStore,Map<Integer, AtomicInteger>> dataFilesInProgress = new ConcurrentHashMap<AMQMessageStore,Map<Integer, AtomicInteger>> ();
private String directoryPath = "";
private RandomAccessFile lockFile;
private FileLock lock;
private boolean disableLocking = DISABLE_LOCKING;
private boolean failIfJournalIsLocked;
private boolean lockLogged;
private boolean lockAquired;
private boolean recoverReferenceStore=true;
private boolean forceRecoverReferenceStore=false;

public String getBrokerName() {
return this.brokerName;
}

public void setBrokerName(String brokerName) {
this.brokerName = brokerName;
if (this.referenceStoreAdapter != null) {
this.referenceStoreAdapter.setBrokerName(brokerName);
}
}

public BrokerService getBrokerService() {
return brokerService;
}

public void setBrokerService(BrokerService brokerService) {
this.brokerService = brokerService;
}

public synchronized void start() throws Exception {
if (!started.compareAndSet(false, true)) {
return;
}
if (this.directory == null) {
if (brokerService != null) {
this.directory = brokerService.getBrokerDataDirectory();

} else {
this.directory = new File(IOHelper.getDefaultDataDirectory(), IOHelper.toFileSystemSafeName(brokerName));
this.directory = new File(directory, "amqstore");
this.directoryPath=directory.getAbsolutePath();
}
}
if (this.directoryArchive == null) {
this.directoryArchive = new File(this.directory,"archive");
}
IOHelper.mkdirs(this.directory);
lockFile = new RandomAccessFile(new File(directory, "lock"), "rw");
lock();
LOG.info("AMQStore starting using directory: " + directory);
if (archiveDataLogs) {
IOHelper.mkdirs(this.directoryArchive);
}

if (this.usageManager != null) {
this.usageManager.getMemoryUsage().addUsageListener(this);
}
if (asyncDataManager == null) {
asyncDataManager = createAsyncDataManager();
}
if (referenceStoreAdapter == null) {
referenceStoreAdapter = createReferenceStoreAdapter();
}
referenceStoreAdapter.setDirectory(new File(directory, "kr-store"));
referenceStoreAdapter.setBrokerName(getBrokerName());
referenceStoreAdapter.setUsageManager(usageManager);
referenceStoreAdapter.setMaxDataFileLength(getMaxReferenceFileLength());
if (taskRunnerFactory == null) {
taskRunnerFactory = createTaskRunnerFactory();
}

if (failIfJournalIsLocked) {
asyncDataManager.lock();
} else {
while (true) {
try {
asyncDataManager.lock();
break;
} catch (IOException e) {
LOG.info("Journal is locked... waiting " + (JOURNAL_LOCKED_WAIT_DELAY / 1000) + " seconds for the journal to be unlocked.", e);
try {
Thread.sleep(JOURNAL_LOCKED_WAIT_DELAY);
} catch (InterruptedException e1) {
}
}
}
}

asyncDataManager.start();
if (deleteAllMessages) {
asyncDataManager.delete();
try {
JournalTrace trace = new JournalTrace();
trace.setMessage("DELETED " + new Date());
Location location = asyncDataManager.write(wireFormat.marshal(trace), false);
asyncDataManager.setMark(location, true);
LOG.info("Journal deleted: ");
deleteAllMessages = false;
} catch (IOException e) {
throw e;
} catch (Throwable e) {
throw IOExceptionSupport.create(e);
}
referenceStoreAdapter.deleteAllMessages();
}
referenceStoreAdapter.start();
Set<Integer> files = referenceStoreAdapter.getReferenceFileIdsInUse();
LOG.info("Active data files: " + files);
checkpointTask = taskRunnerFactory.createTaskRunner(new Task() {

public boolean iterate() {
doCheckpoint();
return false;
}
}, "ActiveMQ Journal Checkpoint Worker");
createTransactionStore();

//
// The following was attempting to reduce startup times by avoiding the
// log
// file scanning that recovery performs. The problem with it is that XA
// transactions
// only live in transaction log and are not stored in the reference
// store, but they still
// need to be recovered when the broker starts up.

if (isForceRecoverReferenceStore()
|| (isRecoverReferenceStore() && !referenceStoreAdapter
.isStoreValid())) {
LOG.warn("The ReferenceStore is not valid - recovering ...");
recover();
LOG.info("Finished recovering the ReferenceStore");
} else {
Location location = writeTraceMessage("RECOVERED " + new Date(),
true);
asyncDataManager.setMark(location, true);
// recover transactions
getTransactionStore().setPreparedTransactions(
referenceStoreAdapter.retrievePreparedState());
}

// Do a checkpoint periodically.
periodicCheckpointTask = new Runnable() {

public void run() {
checkpoint(false);
}
};
scheduler.executePeriodically(periodicCheckpointTask, getCheckpointInterval());
periodicCleanupTask = new Runnable() {

public void run() {
cleanup();
}
};
scheduler.executePeriodically(periodicCleanupTask, getCleanupInterval());

if (lockAquired && lockLogged) {
LOG.info("Aquired lock for AMQ Store" + getDirectory());
if (brokerService != null) {
brokerService.getBroker().nowMasterBroker();
}
}

}

public void stop() throws Exception {

if (!started.compareAndSet(true, false)) {
return;
}
unlock();
if (lockFile != null) {
lockFile.close();
lockFile = null;
}
this.usageManager.getMemoryUsage().removeUsageListener(this);
synchronized (this) {
scheduler.cancel(periodicCheckpointTask);
scheduler.cancel(periodicCleanupTask);
}
Iterator<AMQMessageStore> queueIterator = queues.values().iterator();
while (queueIterator.hasNext()) {
AMQMessageStore ms = queueIterator.next();
ms.stop();
}
Iterator<AMQTopicMessageStore> topicIterator = topics.values().iterator();
while (topicIterator.hasNext()) {
final AMQTopicMessageStore ms = topicIterator.next();
ms.stop();
}
// Take one final checkpoint and stop checkpoint processing.
checkpoint(true);
synchronized (this) {
checkpointTask.shutdown();
}
referenceStoreAdapter.savePreparedState(getTransactionStore().getPreparedTransactions());
queues.clear();
topics.clear();
IOException firstException = null;
referenceStoreAdapter.stop();
referenceStoreAdapter = null;
try {
LOG.debug("Journal close");
asyncDataManager.close();
} catch (Exception e) {
firstException = IOExceptionSupport.create("Failed to close journals: " + e, e);
}
if (firstException != null) {
throw firstException;
}
}

/**
* When we checkpoint we move all the journalled data to long term storage.
*
* @param sync
*/
public void checkpoint(boolean sync) {
try {
if (asyncDataManager == null) {
throw new IllegalStateException("Journal is closed.");
}
CountDownLatch latch = null;
synchronized (this) {
latch = nextCheckpointCountDownLatch;
checkpointTask.wakeup();
}
if (sync) {
if (LOG.isDebugEnabled()) {
LOG.debug("Waitng for checkpoint to complete.");
}
latch.await();
}
referenceStoreAdapter.checkpoint(sync);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
LOG.warn("Request to start checkpoint failed: " + e, e);
} catch (IOException e) {
LOG.error("checkpoint failed: " + e, e);
}
}

/**
* This does the actual checkpoint.
*
* @return true if successful
*/
public boolean doCheckpoint() {
CountDownLatch latch = null;
synchronized (this) {
latch = nextCheckpointCountDownLatch;
nextCheckpointCountDownLatch = new CountDownLatch(1);
}
try {
if (LOG.isDebugEnabled()) {
LOG.debug("Checkpoint started.");
}

Location currentMark = asyncDataManager.getMark();
Location newMark = currentMark;
Iterator<AMQMessageStore> queueIterator = queues.values().iterator();
while (queueIterator.hasNext()) {
final AMQMessageStore ms = queueIterator.next();
Location mark = (Location)ms.getMark();
if (mark != null && (newMark == null || mark.compareTo(newMark) > 0)) {
newMark = mark;
}
}
Iterator<AMQTopicMessageStore> topicIterator = topics.values().iterator();
while (topicIterator.hasNext()) {
final AMQTopicMessageStore ms = topicIterator.next();
Location mark = (Location)ms.getMark();
if (mark != null && (newMark == null || mark.compareTo(newMark) > 0)) {
newMark = mark;
}
}
try {
if (newMark != currentMark) {
if (LOG.isDebugEnabled()) {
LOG.debug("Marking journal at: " + newMark);
}
asyncDataManager.setMark(newMark, false);
writeTraceMessage("CHECKPOINT " + new Date(), true);
}
} catch (Exception e) {
LOG.error("Failed to mark the Journal: " + e, e);
}
if (LOG.isDebugEnabled()) {
LOG.debug("Checkpoint done.");
}
} finally {
latch.countDown();
}
return true;
}

/**
* Cleans up the data files
* @throws IOException
*/
public void cleanup() {
try {
Set<Integer>inProgress = new HashSet<Integer>();
if (LOG.isDebugEnabled()) {
LOG.debug("dataFilesInProgress.values: (" + dataFilesInProgress.values().size() + ") " + dataFilesInProgress.values());
}
for (Map<Integer, AtomicInteger> set: dataFilesInProgress.values()) {
inProgress.addAll(set.keySet());
}
Integer lastDataFile = asyncDataManager.getCurrentDataFileId();
inProgress.add(lastDataFile);
lastDataFile = asyncDataManager.getMark().getDataFileId();
inProgress.addAll(referenceStoreAdapter.getReferenceFileIdsInUse());
Location lastActiveTx = transactionStore.checkpoint();
if (lastActiveTx != null) {
lastDataFile = Math.min(lastDataFile, lastActiveTx.getDataFileId());
}
LOG.debug("lastDataFile: " + lastDataFile);
asyncDataManager.consolidateDataFilesNotIn(inProgress, lastDataFile - 1);
} catch (IOException e) {
LOG.error("Could not cleanup data files: " + e, e);
}
}

public Set<ActiveMQDestination> getDestinations() {
Set<ActiveMQDestination> destinations = new HashSet<ActiveMQDestination>(referenceStoreAdapter.getDestinations());
destinations.addAll(queues.keySet());
destinations.addAll(topics.keySet());
return destinations;
}

MessageStore createMessageStore(ActiveMQDestination destination) throws IOException {
if (destination.isQueue()) {
return createQueueMessageStore((ActiveMQQueue)destination);
} else {
return createTopicMessageStore((ActiveMQTopic)destination);
}
}

public MessageStore createQueueMessageStore(ActiveMQQueue destination) throws IOException {
AMQMessageStore store = queues.get(destination);
if (store == null) {
ReferenceStore checkpointStore = referenceStoreAdapter.createQueueReferenceStore(destination);
store = new AMQMessageStore(this, checkpointStore, destination);
try {
store.start();
} catch (Exception e) {
throw IOExceptionSupport.create(e);
}
queues.put(destination, store);
}
return store;
}

public TopicMessageStore createTopicMessageStore(ActiveMQTopic destinationName) throws IOException {
AMQTopicMessageStore store = topics.get(destinationName);
if (store == null) {
TopicReferenceStore checkpointStore = referenceStoreAdapter.createTopicReferenceStore(destinationName);
store = new AMQTopicMessageStore(this,checkpointStore, destinationName);
try {
store.start();
} catch (Exception e) {
throw IOExceptionSupport.create(e);
}
topics.put(destinationName, store);
}
return store;
}

/**
* Cleanup method to remove any state associated with the given destination
*
* @param destination
*/
public void removeQueueMessageStore(ActiveMQQueue destination) {
AMQMessageStore store= queues.remove(destination);
referenceStoreAdapter.removeQueueMessageStore(destination);
}

/**
* Cleanup method to remove any state associated with the given destination
*
* @param destination
*/
public void removeTopicMessageStore(ActiveMQTopic destination) {
topics.remove(destination);
}

public TransactionStore createTransactionStore() throws IOException {
return transactionStore;
}

public long getLastMessageBrokerSequenceId() throws IOException {
return referenceStoreAdapter.getLastMessageBrokerSequenceId();
}

public void beginTransaction(ConnectionContext context) throws IOException {
referenceStoreAdapter.beginTransaction(context);
}

public void commitTransaction(ConnectionContext context) throws IOException {
referenceStoreAdapter.commitTransaction(context);
}

public void rollbackTransaction(ConnectionContext context) throws IOException {
referenceStoreAdapter.rollbackTransaction(context);
}

public boolean isPersistentIndex() {
return persistentIndex;
}

public void setPersistentIndex(boolean persistentIndex) {
this.persistentIndex = persistentIndex;
}

/**
* @param location
* @return
* @throws IOException
*/
public DataStructure readCommand(Location location) throws IOException {
try {
ByteSequence packet = asyncDataManager.read(location);
return (DataStructure)wireFormat.unmarshal(packet);
} catch (IOException e) {
throw createReadException(location, e);
}
}

/**
* Move all the messages that were in the journal into long term storage. We
* just replay and do a checkpoint.
*
* @throws IOException
* @throws IOException
* @throws InvalidLocationException
* @throws IllegalStateException
*/
private void recover() throws IllegalStateException, IOException {
referenceStoreAdapter.clearMessages();
Location pos = null;
int redoCounter = 0;
LOG.info("Journal Recovery Started from: " + asyncDataManager);
long start = System.currentTimeMillis();
ConnectionContext context = new ConnectionContext(new NonCachedMessageEvaluationContext());
// While we have records in the journal.
while ((pos = asyncDataManager.getNextLocation(pos)) != null) {
ByteSequence data = asyncDataManager.read(pos);
DataStructure c = (DataStructure)wireFormat.unmarshal(data);
if (c instanceof Message) {
Message message = (Message)c;
AMQMessageStore store = (AMQMessageStore)createMessageStore(message.getDestination());
if (message.isInTransaction()) {
transactionStore.addMessage(store, message, pos);
} else {
if (store.replayAddMessage(context, message, pos)) {
redoCounter++;
}
}
} else {
switch (c.getDataStructureType()) {
case SubscriptionInfo.DATA_STRUCTURE_TYPE: {
referenceStoreAdapter.recoverSubscription((SubscriptionInfo)c);
}
break;
case JournalQueueAck.DATA_STRUCTURE_TYPE: {
JournalQueueAck command = (JournalQueueAck)c;
AMQMessageStore store = (AMQMessageStore)createMessageStore(command.getDestination());
if (command.getMessageAck().isInTransaction()) {
transactionStore.removeMessage(store, command.getMessageAck(), pos);
} else {
if (store.replayRemoveMessage(context, command.getMessageAck())) {
redoCounter++;
}
}
}
break;
case JournalTopicAck.DATA_STRUCTURE_TYPE: {
JournalTopicAck command = (JournalTopicAck)c;
AMQTopicMessageStore store = (AMQTopicMessageStore)createMessageStore(command.getDestination());
if (command.getTransactionId() != null) {
transactionStore.acknowledge(store, command, pos);
} else {
if (store.replayAcknowledge(context, command.getClientId(), command.getSubscritionName(), command.getMessageId())) {
redoCounter++;
}
}
}
break;
case JournalTransaction.DATA_STRUCTURE_TYPE: {
JournalTransaction command = (JournalTransaction)c;
try {
// Try to replay the packet.
switch (command.getType()) {
case JournalTransaction.XA_PREPARE:
transactionStore.replayPrepare(command.getTransactionId());
break;
case JournalTransaction.XA_COMMIT:
case JournalTransaction.LOCAL_COMMIT:
AMQTx tx = transactionStore.replayCommit(command.getTransactionId(), command.getWasPrepared());
if (tx == null) {
break; // We may be trying to replay a commit
}
// that
// was already committed.
// Replay the committed operations.
tx.getOperations();
for (Iterator iter = tx.getOperations().iterator(); iter.hasNext();) {
AMQTxOperation op = (AMQTxOperation)iter.next();
if (op.replay(this, context)) {
redoCounter++;
}
}
break;
case JournalTransaction.LOCAL_ROLLBACK:
case JournalTransaction.XA_ROLLBACK:
transactionStore.replayRollback(command.getTransactionId());
break;
default:
throw new IOException("Invalid journal command type: " + command.getType());
}
} catch (IOException e) {
LOG.error("Recovery Failure: Could not replay: " + c + ", reason: " + e, e);
}
}
break;
case JournalTrace.DATA_STRUCTURE_TYPE:
JournalTrace trace = (JournalTrace)c;
LOG.debug("TRACE Entry: " + trace.getMessage());
break;
default:
LOG.error("Unknown type of record in transaction log which will be discarded: " + c);
}
}
}
Location location = writeTraceMessage("RECOVERED " + new Date(), true);
asyncDataManager.setMark(location, true);
long end = System.currentTimeMillis();
LOG.info("Recovered " + redoCounter + " operations from redo log in " + ((end - start) / 1000.0f) + " seconds.");
}

private IOException createReadException(Location location, Exception e) {
return IOExceptionSupport.create("Failed to read to journal for: " + location + ". Reason: " + e, e);
}

protected IOException createWriteException(DataStructure packet, Exception e) {
return IOExceptionSupport.create("Failed to write to journal for: " + packet + ". Reason: " + e, e);
}

protected IOException createWriteException(String command, Exception e) {
return IOExceptionSupport.create("Failed to write to journal for command: " + command + ". Reason: " + e, e);
}

protected IOException createRecoveryFailedException(Exception e) {
return IOExceptionSupport.create("Failed to recover from journal. Reason: " + e, e);
}

/**
* @param command
* @param syncHint
* @return
* @throws IOException
*/
public Location writeCommand(DataStructure command, boolean syncHint) throws IOException {
return writeCommand(command, syncHint,false);
}

public Location writeCommand(DataStructure command, boolean syncHint,boolean forceSync) throws IOException {
return asyncDataManager.write(wireFormat.marshal(command), (forceSync||(syncHint && syncOnWrite)));
}

private Location writeTraceMessage(String message, boolean sync) throws IOException {
JournalTrace trace = new JournalTrace();
trace.setMessage(message);
return writeCommand(trace, sync);
}

public void onUsageChanged(Usage usage, int oldPercentUsage, int newPercentUsage) {
newPercentUsage = (newPercentUsage / 10) * 10;
oldPercentUsage = (oldPercentUsage / 10) * 10;
if (newPercentUsage >= 70 && oldPercentUsage < newPercentUsage) {
checkpoint(false);
}
}

public AMQTransactionStore getTransactionStore() {
return transactionStore;
}

public synchronized void deleteAllMessages() throws IOException {
deleteAllMessages = true;
}

public String toString() {
return "AMQPersistenceAdapter(" + directory + ")";
}

// /////////////////////////////////////////////////////////////////
// Subclass overridables
// /////////////////////////////////////////////////////////////////
protected AsyncDataManager createAsyncDataManager() {
AsyncDataManager manager = new AsyncDataManager(storeSize);
manager.setDirectory(new File(directory, "journal"));
manager.setDirectoryArchive(getDirectoryArchive());
manager.setArchiveDataLogs(isArchiveDataLogs());
manager.setMaxFileLength(maxFileLength);
manager.setUseNio(useNio);
return manager;
}

protected KahaReferenceStoreAdapter createReferenceStoreAdapter() throws IOException {
KahaReferenceStoreAdapter adaptor = new KahaReferenceStoreAdapter(storeSize);
adaptor.setPersistentIndex(isPersistentIndex());
adaptor.setIndexBinSize(getIndexBinSize());
adaptor.setIndexKeySize(getIndexKeySize());
adaptor.setIndexPageSize(getIndexPageSize());
adaptor.setIndexMaxBinSize(getIndexMaxBinSize());
adaptor.setIndexLoadFactor(getIndexLoadFactor());
return adaptor;
}

protected TaskRunnerFactory createTaskRunnerFactory() {
return DefaultThreadPools.getDefaultTaskRunnerFactory();
}

// /////////////////////////////////////////////////////////////////
// Property Accessors
// /////////////////////////////////////////////////////////////////
public AsyncDataManager getAsyncDataManager() {
return asyncDataManager;
}

public void setAsyncDataManager(AsyncDataManager asyncDataManager) {
this.asyncDataManager = asyncDataManager;
}

public ReferenceStoreAdapter getReferenceStoreAdapter() {
return referenceStoreAdapter;
}

public TaskRunnerFactory getTaskRunnerFactory() {
return taskRunnerFactory;
}

public void setTaskRunnerFactory(TaskRunnerFactory taskRunnerFactory) {
this.taskRunnerFactory = taskRunnerFactory;
}

/**
* @return Returns the wireFormat.
*/
public WireFormat getWireFormat() {
return wireFormat;
}

public void setWireFormat(WireFormat wireFormat) {
this.wireFormat = wireFormat;
}

public SystemUsage getUsageManager() {
return usageManager;
}

public void setUsageManager(SystemUsage usageManager) {
this.usageManager = usageManager;
}

public int getMaxCheckpointMessageAddSize() {
return maxCheckpointMessageAddSize;
}

/**
* When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can be used
* @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryIntPropertyEditor"
*/
public void setMaxCheckpointMessageAddSize(int maxCheckpointMessageAddSize) {
this.maxCheckpointMessageAddSize = maxCheckpointMessageAddSize;
}


public synchronized File getDirectory() {
return directory;
}

public synchronized void setDirectory(File directory) {
this.directory = directory;
}

public boolean isSyncOnWrite() {
return this.syncOnWrite;
}

public void setSyncOnWrite(boolean syncOnWrite) {
this.syncOnWrite = syncOnWrite;
}

public boolean isSyncOnTransaction() {
return syncOnTransaction;
}

public void setSyncOnTransaction(boolean syncOnTransaction) {
this.syncOnTransaction = syncOnTransaction;
}

/**
* @param referenceStoreAdapter the referenceStoreAdapter to set
*/
public void setReferenceStoreAdapter(ReferenceStoreAdapter referenceStoreAdapter) {
this.referenceStoreAdapter = referenceStoreAdapter;
}

public long size(){
return storeSize.get();
}

public boolean isUseNio() {
return useNio;
}

public void setUseNio(boolean useNio) {
this.useNio = useNio;
}

public int getMaxFileLength() {
return maxFileLength;
}

/**
* When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can be used
* @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryPropertyEditor"
*/
public void setMaxFileLength(int maxFileLength) {
this.maxFileLength = maxFileLength;
}

public long getCleanupInterval() {
return cleanupInterval;
}

public void setCleanupInterval(long cleanupInterval) {
this.cleanupInterval = cleanupInterval;
}

public long getCheckpointInterval() {
return checkpointInterval;
}

public void setCheckpointInterval(long checkpointInterval) {
this.checkpointInterval = checkpointInterval;
}

public int getIndexBinSize() {
return indexBinSize;
}

public void setIndexBinSize(int indexBinSize) {
this.indexBinSize = indexBinSize;
}

public int getIndexKeySize() {
return indexKeySize;
}

public void setIndexKeySize(int indexKeySize) {
this.indexKeySize = indexKeySize;
}

public int getIndexPageSize() {
return indexPageSize;
}

public int getIndexMaxBinSize() {
return indexMaxBinSize;
}

public void setIndexMaxBinSize(int maxBinSize) {
this.indexMaxBinSize = maxBinSize;
}

/**
* When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can be used
* @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryPropertyEditor"
*/
public void setIndexPageSize(int indexPageSize) {
this.indexPageSize = indexPageSize;
}

public void setIndexLoadFactor(int factor){
this.indexLoadFactor=factor;
}

public int getIndexLoadFactor(){
return this.indexLoadFactor;
}

public int getMaxReferenceFileLength() {
return maxReferenceFileLength;
}

/**
* When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can be used
* @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryPropertyEditor"
*/
public void setMaxReferenceFileLength(int maxReferenceFileLength) {
this.maxReferenceFileLength = maxReferenceFileLength;
}

public File getDirectoryArchive() {
return directoryArchive;
}

public void setDirectoryArchive(File directoryArchive) {
this.directoryArchive = directoryArchive;
}

public boolean isArchiveDataLogs() {
return archiveDataLogs;
}

public void setArchiveDataLogs(boolean archiveDataLogs) {
this.archiveDataLogs = archiveDataLogs;
}

public boolean isDisableLocking() {
return disableLocking;
}

public void setDisableLocking(boolean disableLocking) {
this.disableLocking = disableLocking;
}

/**
* @return the recoverReferenceStore
*/
public boolean isRecoverReferenceStore() {
return recoverReferenceStore;
}

/**
* @param recoverReferenceStore the recoverReferenceStore to set
*/
public void setRecoverReferenceStore(boolean recoverReferenceStore) {
this.recoverReferenceStore = recoverReferenceStore;
}

/**
* @return the forceRecoverReferenceStore
*/
public boolean isForceRecoverReferenceStore() {
return forceRecoverReferenceStore;
}

/**
* @param forceRecoverReferenceStore the forceRecoverReferenceStore to set
*/
public void setForceRecoverReferenceStore(boolean forceRecoverReferenceStore) {
this.forceRecoverReferenceStore = forceRecoverReferenceStore;
}


protected void addInProgressDataFile(AMQMessageStore store,int dataFileId) {
Map<Integer, AtomicInteger> map = dataFilesInProgress.get(store);
if (map == null) {
map = new ConcurrentHashMap<Integer, AtomicInteger>();
dataFilesInProgress.put(store, map);
}
AtomicInteger count = map.get(dataFileId);
if (count == null) {
count = new AtomicInteger(0);
map.put(dataFileId, count);
}
count.incrementAndGet();
}

protected void removeInProgressDataFile(AMQMessageStore store,int dataFileId) {
Map<Integer, AtomicInteger> map = dataFilesInProgress.get(store);
if (map != null) {
AtomicInteger count = map.get(dataFileId);
if (count != null) {
int newCount = count.decrementAndGet();
if (newCount <=0) {
map.remove(dataFileId);
}
}
if (map.isEmpty()) {
dataFilesInProgress.remove(store);
}
}
}



protected void lock() throws Exception {
lockLogged = false;
lockAquired = false;
do {
if (doLock()) {
lockAquired = true;
} else {
if (!lockLogged) {
LOG.warn("Waiting to Lock the Store " + getDirectory());
lockLogged = true;
}
Thread.sleep(1000);
}

} while (!lockAquired && !disableLocking);
}

private synchronized void unlock() throws IOException {
if (!disableLocking && (null != lock)) {
//clear property doesn't work on some platforms
System.getProperties().remove(getPropertyKey());
System.clearProperty(getPropertyKey());
assert(System.getProperty(getPropertyKey())==null);
if (lock.isValid()) {
lock.release();
lock.channel().close();

}
lock = null;
}
}


protected boolean doLock() throws IOException {
boolean result = true;
if (!disableLocking && directory != null && lock == null) {
String key = getPropertyKey();
String property = System.getProperty(key);
if (null == property) {
if (!BROKEN_FILE_LOCK) {
lock = lockFile.getChannel().tryLock();
if (lock == null) {
result = false;
} else {
System.setProperty(key, new Date().toString());
}
}
} else { // already locked
result = false;
}
}
return result;
}

private String getPropertyKey() throws IOException {
return getClass().getName() + ".lock." + directory.getCanonicalPath();
}

static {
BROKEN_FILE_LOCK = "true".equals(System.getProperty(PROPERTY_PREFIX
+ ".FileLockBroken",
"false"));
DISABLE_LOCKING = "true".equals(System.getProperty(PROPERTY_PREFIX
+ ".DisableLocking",
"false"));
}
}