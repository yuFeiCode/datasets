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
package org.apache.activemq.broker.region;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.ProducerBrokerExchange;
import org.apache.activemq.broker.region.cursors.PendingMessageCursor;
import org.apache.activemq.broker.region.cursors.StoreQueueCursor;
import org.apache.activemq.broker.region.cursors.VMPendingMessageCursor;
import org.apache.activemq.broker.region.group.MessageGroupHashBucketFactory;
import org.apache.activemq.broker.region.group.MessageGroupMap;
import org.apache.activemq.broker.region.group.MessageGroupMapFactory;
import org.apache.activemq.broker.region.policy.DispatchPolicy;
import org.apache.activemq.broker.region.policy.RoundRobinDispatchPolicy;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ConsumerId;
import org.apache.activemq.command.ExceptionResponse;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageDispatchNotification;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.ProducerAck;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.filter.BooleanExpression;
import org.apache.activemq.filter.MessageEvaluationContext;
import org.apache.activemq.filter.NonCachedMessageEvaluationContext;
import org.apache.activemq.selector.SelectorParser;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.thread.DeterministicTaskRunner;
import org.apache.activemq.thread.Scheduler;
import org.apache.activemq.thread.Task;
import org.apache.activemq.thread.TaskRunner;
import org.apache.activemq.thread.TaskRunnerFactory;
import org.apache.activemq.transaction.Synchronization;
import org.apache.activemq.usage.Usage;
import org.apache.activemq.usage.UsageListener;
import org.apache.activemq.util.BrokerSupport;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.jms.InvalidSelectorException;
import javax.jms.JMSException;


/**
* The Queue is a List of MessageEntry objects that are dispatched to matching
* subscriptions.
*
* @version $Revision: 1.28 $
*/
public class Queue extends BaseDestination implements Task, UsageListener {
protected static final Log LOG = LogFactory.getLog(Queue.class);
protected TaskRunnerFactory taskFactory;
protected TaskRunner taskRunner;
protected final List<Subscription> consumers = new ArrayList<Subscription>(50);
protected PendingMessageCursor messages;
private final LinkedHashMap<MessageId,QueueMessageReference> pagedInMessages = new LinkedHashMap<MessageId,QueueMessageReference>();
// Messages that are paged in but have not yet been targeted at a subscription
private List<QueueMessageReference> pagedInPendingDispatch = new ArrayList<QueueMessageReference>(100);
private MessageGroupMap messageGroupOwners;
private DispatchPolicy dispatchPolicy = new RoundRobinDispatchPolicy();
private MessageGroupMapFactory messageGroupMapFactory = new MessageGroupHashBucketFactory();
private final Object sendLock = new Object();
private ExecutorService executor;
protected final LinkedList<Runnable> messagesWaitingForSpace = new LinkedList<Runnable>();
private final Object dispatchMutex = new Object();
private boolean useConsumerPriority=true;
private boolean strictOrderDispatch=false;
private QueueDispatchSelector dispatchSelector;
private boolean optimizedDispatch=false;
private boolean firstConsumer = false;
private int timeBeforeDispatchStarts = 0;
private int consumersBeforeDispatchStarts = 0;
private CountDownLatch consumersBeforeStartsLatch;
private final Runnable sendMessagesWaitingForSpaceTask = new Runnable() {
public void run() {
wakeup();
}
};
private final Runnable expireMessagesTask = new Runnable() {
public void run() {
expireMessages();
}
};
private final Object iteratingMutex = new Object() {};
private static final Scheduler scheduler = Scheduler.getInstance();

private static final Comparator<Subscription>orderedCompare = new Comparator<Subscription>() {

public int compare(Subscription s1, Subscription s2) {
//We want the list sorted in descending order
return s2.getConsumerInfo().getPriority() - s1.getConsumerInfo().getPriority();
}
};

public Queue(BrokerService brokerService, final ActiveMQDestination destination, MessageStore store,DestinationStatistics parentStats,
TaskRunnerFactory taskFactory) throws Exception {
super(brokerService, store, destination, parentStats);
this.taskFactory=taskFactory;
this.dispatchSelector=new QueueDispatchSelector(destination);
}

public List<Subscription> getConsumers() {
synchronized (consumers) {
return new ArrayList<Subscription>(consumers);
}
}

// make the queue easily visible in the debugger from its task runner threads
final class QueueThread extends Thread {
final Queue queue;
public QueueThread(Runnable runnable, String name,
Queue queue) {
super(runnable, name);
this.queue = queue;
}
}

public void initialize() throws Exception {
if (this.messages == null) {
if (destination.isTemporary() || broker == null || store == null) {
this.messages = new VMPendingMessageCursor();
} else {
this.messages = new StoreQueueCursor(broker, this);
}
}
// If a VMPendingMessageCursor don't use the default Producer System Usage
// since it turns into a shared blocking queue which can lead to a network deadlock.
// If we are cursoring to disk..it's not and issue because it does not block due
// to large disk sizes.
if( messages instanceof VMPendingMessageCursor ) {
this.systemUsage = brokerService.getSystemUsage();
memoryUsage.setParent(systemUsage.getMemoryUsage());
}

if (isOptimizedDispatch()) {
this.taskRunner = taskFactory.createTaskRunner(this, "TempQueue:  " + destination.getPhysicalName());
}else {
final Queue queue = this;
this.executor =  Executors.newSingleThreadExecutor(new ThreadFactory() {
public Thread newThread(Runnable runnable) {
Thread thread = new QueueThread(runnable, "QueueThread:"+destination, queue);
thread.setDaemon(true);
thread.setPriority(Thread.NORM_PRIORITY);
return thread;
}
});

this.taskRunner = new DeterministicTaskRunner(this.executor,this);
}

super.initialize();
if (store != null) {
// Restore the persistent messages.
messages.setSystemUsage(systemUsage);
messages.setEnableAudit(isEnableAudit());
messages.setMaxAuditDepth(getMaxAuditDepth());
messages.setMaxProducersToAudit(getMaxProducersToAudit());
messages.setUseCache(isUseCache());
messages.setMemoryUsageHighWaterMark(getCursorMemoryHighWaterMark());
if (messages.isRecoveryRequired()) {
store.recover(new MessageRecoveryListener() {

public boolean recoverMessage(Message message) {
// Message could have expired while it was being
// loaded..
if (broker.isExpired(message)) {
messageExpired(createConnectionContext(), createMessageReference(message));
// drop message will decrement so counter balance here
destinationStatistics.getMessages().increment();
return true;
}
if (hasSpace()) {
message.setRegionDestination(Queue.this);
synchronized (messages) {
try {
messages.addMessageLast(message);
} catch (Exception e) {
LOG.fatal("Failed to add message to cursor", e);
}
}
destinationStatistics.getMessages().increment();
return true;
}
return false;
}

public boolean recoverMessageReference(MessageId messageReference) throws Exception {
throw new RuntimeException("Should not be called.");
}

public boolean hasSpace() {
return true;
}

public boolean isDuplicate(MessageId id) {
return false;
}
});
}else {
int messageCount = store.getMessageCount();
destinationStatistics.getMessages().setCount(messageCount);
}
}
}

/*
* Holder for subscription and pagedInMessages as a browser
* needs access to existing messages in the queue that have
* already been dispatched
*/
class BrowserDispatch {
ArrayList<QueueMessageReference> messages;
QueueBrowserSubscription browser;

public BrowserDispatch(QueueBrowserSubscription browserSubscription,
Collection<QueueMessageReference> values) {

messages =  new ArrayList<QueueMessageReference>(values);
browser = browserSubscription;
browser.incrementQueueRef();
}

void done() {
try {
browser.decrementQueueRef();
} catch (Exception e) {
LOG.warn("decrement ref on browser: " + browser, e);
}
}

public QueueBrowserSubscription getBrowser() {
return browser;
}
}

LinkedList<BrowserDispatch> browserDispatches = new LinkedList<BrowserDispatch>();

public void addSubscription(ConnectionContext context, Subscription sub) throws Exception {
// synchronize with dispatch method so that no new messages are sent
// while setting up a subscription. avoid out of order messages,
// duplicates, etc.
synchronized(dispatchMutex) {

sub.add(context, this);
destinationStatistics.getConsumers().increment();

// needs to be synchronized - so no contention with dispatching
synchronized (consumers) {

// set a flag if this is a first consumer
if (consumers.size() == 0) {
firstConsumer = true;
if (consumersBeforeDispatchStarts != 0) {
consumersBeforeStartsLatch = new CountDownLatch(consumersBeforeDispatchStarts - 1);
}
} else {
if (consumersBeforeStartsLatch != null) {
consumersBeforeStartsLatch.countDown();
}
}

addToConsumerList(sub);
if (sub.getConsumerInfo().isExclusive()) {
Subscription exclusiveConsumer = dispatchSelector.getExclusiveConsumer();
if(exclusiveConsumer==null) {
exclusiveConsumer=sub;
}else if (sub.getConsumerInfo().getPriority() > exclusiveConsumer.getConsumerInfo().getPriority()){
exclusiveConsumer=sub;
}
dispatchSelector.setExclusiveConsumer(exclusiveConsumer);
}
}

if (sub instanceof QueueBrowserSubscription ) {
QueueBrowserSubscription browserSubscription = (QueueBrowserSubscription) sub;

// do again in iterate to ensure new messages are dispatched
pageInMessages(false);

synchronized (pagedInMessages) {
if (!pagedInMessages.isEmpty()) {
BrowserDispatch browserDispatch = new BrowserDispatch(browserSubscription, pagedInMessages.values());
browserDispatches.addLast(browserDispatch);
}
}
}
if (!(this.optimizedDispatch || isSlave())) {
wakeup();
}
}
if (this.optimizedDispatch || isSlave()) {
// Outside of dispatchLock() to maintain the lock hierarchy of
// iteratingMutex -> dispatchLock. - see https://issues.apache.org/activemq/browse/AMQ-1878
wakeup();
}
}

public void removeSubscription(ConnectionContext context, Subscription sub, long lastDeiveredSequenceId)
throws Exception {
destinationStatistics.getConsumers().decrement();
// synchronize with dispatch method so that no new messages are sent
// while removing up a subscription.
synchronized(dispatchMutex) {
if (LOG.isDebugEnabled()) {
LOG.debug("remove sub: " + sub + ", lastDeliveredSeqId: " + lastDeiveredSequenceId
+ ", dequeues: " + getDestinationStatistics().getDequeues().getCount()
+ ", dispatched: " + getDestinationStatistics().getDispatched().getCount()
+ ", inflight: " + getDestinationStatistics().getInflight().getCount());
}
synchronized (consumers) {
removeFromConsumerList(sub);
if (sub.getConsumerInfo().isExclusive()) {
Subscription exclusiveConsumer = dispatchSelector
.getExclusiveConsumer();
if (exclusiveConsumer == sub) {
exclusiveConsumer = null;
for (Subscription s : consumers) {
if (s.getConsumerInfo().isExclusive()
&& (exclusiveConsumer == null
|| s.getConsumerInfo().getPriority() > exclusiveConsumer
.getConsumerInfo().getPriority())) {
exclusiveConsumer = s;

}
}
dispatchSelector.setExclusiveConsumer(exclusiveConsumer);
}
}
ConsumerId consumerId = sub.getConsumerInfo().getConsumerId();
getMessageGroupOwners().removeConsumer(consumerId);

// redeliver inflight messages
List<QueueMessageReference> list = new ArrayList<QueueMessageReference>();
for (MessageReference ref : sub.remove(context, this)) {
QueueMessageReference qmr = (QueueMessageReference)ref;
if( qmr.getLockOwner()==sub ) {
qmr.unlock();
// only increment redelivery if it was delivered or we have no delivery information
if (lastDeiveredSequenceId == 0 || qmr.getMessageId().getBrokerSequenceId() <= lastDeiveredSequenceId) {
qmr.incrementRedeliveryCounter();
}
}
list.add(qmr);
}

if (!list.isEmpty()) {
doDispatch(list);
}
}
if (!(this.optimizedDispatch || isSlave())) {
wakeup();
}
}
if (this.optimizedDispatch || isSlave()) {
// Outside of dispatchLock() to maintain the lock hierarchy of
// iteratingMutex -> dispatchLock. - see https://issues.apache.org/activemq/browse/AMQ-1878
wakeup();
}
}

public void send(final ProducerBrokerExchange producerExchange, final Message message) throws Exception {
final ConnectionContext context = producerExchange.getConnectionContext();
// There is delay between the client sending it and it arriving at the
// destination.. it may have expired.
message.setRegionDestination(this);
final ProducerInfo producerInfo = producerExchange.getProducerState().getInfo();
final boolean sendProducerAck = !message.isResponseRequired() && producerInfo.getWindowSize() > 0 && !context.isInRecoveryMode();
if (message.isExpired()) {
//message not stored - or added to stats yet - so chuck here
broker.getRoot().messageExpired(context, message);
if (sendProducerAck) {
ProducerAck ack = new ProducerAck(producerInfo.getProducerId(), message.getSize());
context.getConnection().dispatchAsync(ack);
}
return;
}
if(memoryUsage.isFull()) {
isFull(context, memoryUsage);
fastProducer(context, producerInfo);
if (isProducerFlowControl() && context.isProducerFlowControl()) {
if(warnOnProducerFlowControl) {
warnOnProducerFlowControl = false;
LOG.info("Usage Manager memory limit reached on " +getActiveMQDestination().getQualifiedName() + ". Producers will be throttled to the rate at which messages are removed from this destination to prevent flooding it." +
" See http://activemq.apache.org/producer-flow-control.html for more info");
}

if (systemUsage.isSendFailIfNoSpace()) {
throw new javax.jms.ResourceAllocationException("Usage Manager memory limit reached. Stopping producer (" + message.getProducerId() + ") to prevent flooding " +getActiveMQDestination().getQualifiedName() + "." +
" See http://activemq.apache.org/producer-flow-control.html for more info");
}

// We can avoid blocking due to low usage if the producer is sending
// a sync message or
// if it is using a producer window
if (producerInfo.getWindowSize() > 0 || message.isResponseRequired()) {
// copy the exchange state since the context will be modified while we are waiting
// for space.
final ProducerBrokerExchange producerExchangeCopy = producerExchange.copy();
synchronized (messagesWaitingForSpace) {
messagesWaitingForSpace.add(new Runnable() {
public void run() {

try {
// While waiting for space to free up... the
// message may have expired.
if (message.isExpired()) {
broker.messageExpired(context, message);
destinationStatistics.getExpired().increment();
} else {
doMessageSend(producerExchangeCopy, message);
}

if (sendProducerAck) {
ProducerAck ack = new ProducerAck(producerInfo.getProducerId(), message.getSize());
context.getConnection().dispatchAsync(ack);
} else {
Response response = new Response();
response.setCorrelationId(message.getCommandId());
context.getConnection().dispatchAsync(response);
}

} catch (Exception e) {
if (!sendProducerAck && !context.isInRecoveryMode()) {
ExceptionResponse response = new ExceptionResponse(e);
response.setCorrelationId(message.getCommandId());
context.getConnection().dispatchAsync(response);
}
}
}
});

// If the user manager is not full, then the task will not
// get called..
if (!memoryUsage.notifyCallbackWhenNotFull(sendMessagesWaitingForSpaceTask)) {
// so call it directly here.
sendMessagesWaitingForSpaceTask.run();
}
context.setDontSendReponse(true);
return;
}

} else {

// Producer flow control cannot be used, so we have do the flow
// control at the broker
// by blocking this thread until there is space available.
while (!memoryUsage.waitForSpace(1000)) {
if (context.getStopping().get()) {
throw new IOException("Connection closed, send aborted.");
}
}

// The usage manager could have delayed us by the time
// we unblock the message could have expired..
if (message.isExpired()) {
if (LOG.isDebugEnabled()) {
LOG.debug("Expired message: " + message);
}
broker.getRoot().messageExpired(context, message);
return;
}
}
}
}
doMessageSend(producerExchange, message);
if (sendProducerAck) {
ProducerAck ack = new ProducerAck(producerInfo.getProducerId(), message.getSize());
context.getConnection().dispatchAsync(ack);
}
}

void doMessageSend(final ProducerBrokerExchange producerExchange, final Message message) throws IOException, Exception {
final ConnectionContext context = producerExchange.getConnectionContext();
synchronized (sendLock) {
if (store != null && message.isPersistent()) {
if (systemUsage.getStoreUsage().isFull()) {
final String logMessage = "Usage Manager Store is Full. Stopping producer (" + message.getProducerId() + ") to prevent flooding " + getActiveMQDestination().getQualifiedName() + "." +
" See http://activemq.apache.org/producer-flow-control.html for more info";
LOG.info(logMessage);
if (systemUsage.isSendFailIfNoSpace()) {
throw new javax.jms.ResourceAllocationException(logMessage);
}
}
while (!systemUsage.getStoreUsage().waitForSpace(1000)) {
if (context.getStopping().get()) {
throw new IOException(
"Connection closed, send aborted.");
}
LOG.debug(this  + ", waiting for store space... msg: " + message);
}
message.getMessageId().setBrokerSequenceId(getDestinationSequenceId());
store.addMessage(context, message);

}
}
if (context.isInTransaction()) {
// If this is a transacted message.. increase the usage now so that
// a big TX does not blow up
// our memory. This increment is decremented once the tx finishes..
message.incrementReferenceCount();
context.getTransaction().addSynchronization(new Synchronization() {
public void afterCommit() throws Exception {
try {
// It could take while before we receive the commit
// op, by that time the message could have expired..
if (broker.isExpired(message)) {
broker.messageExpired(context, message);
destinationStatistics.getExpired().increment();
return;
}
sendMessage(context, message);
} finally {
message.decrementReferenceCount();
}
}

@Override
public void afterRollback() throws Exception {
message.decrementReferenceCount();
}
});
} else {
// Add to the pending list, this takes care of incrementing the
// usage manager.
sendMessage(context, message);
}
}

private void expireMessages() {
if (LOG.isDebugEnabled()) {
LOG.debug("Expiring messages ..");
}

// just track the insertion count
List<Message> browsedMessages = new AbstractList<Message>() {
int size = 0;

@Override
public void add(int index, Message element) {
size++;
}

@Override
public int size() {
return size;
}

@Override
public Message get(int index) {
return null;
}
};
doBrowse(true, browsedMessages, this.getMaxExpirePageSize());
}

public void gc(){
}

public void acknowledge(ConnectionContext context, Subscription sub, MessageAck ack, MessageReference node) throws IOException {
messageConsumed(context, node);
if (store != null && node.isPersistent()) {
// the original ack may be a ranged ack, but we are trying to delete a specific
// message store here so we need to convert to a non ranged ack.
if (ack.getMessageCount() > 0) {
// Dup the ack
MessageAck a = new MessageAck();
ack.copy(a);
ack = a;
// Convert to non-ranged.
ack.setFirstMessageId(node.getMessageId());
ack.setLastMessageId(node.getMessageId());
ack.setMessageCount(1);
}
store.removeMessage(context, ack);
}
}

Message loadMessage(MessageId messageId) throws IOException {
Message msg = store.getMessage(messageId);
if (msg != null) {
msg.setRegionDestination(this);
}
return msg;
}

public String toString() {
int size = 0;
synchronized (messages) {
size = messages.size();
}
return "Queue: destination=" + destination.getPhysicalName() + ", subscriptions=" + consumers.size() + ", memory=" + memoryUsage.getPercentUsage() + "%, size=" + size
+ ", in flight groups=" + messageGroupOwners;
}

public void start() throws Exception {
if (memoryUsage != null) {
memoryUsage.start();
}
systemUsage.getMemoryUsage().addUsageListener(this);
messages.start();
if (getExpireMessagesPeriod() > 0) {
scheduler.schedualPeriodically(expireMessagesTask, getExpireMessagesPeriod());
}
doPageIn(false);
}

public void stop() throws Exception{
if (taskRunner != null) {
taskRunner.shutdown();
}
if (this.executor != null) {
this.executor.shutdownNow();
}

scheduler.cancel(expireMessagesTask);

if (messages != null) {
messages.stop();
}

systemUsage.getMemoryUsage().removeUsageListener(this);
if (memoryUsage != null) {
memoryUsage.stop();
}
if (store!=null) {
store.stop();
}
}

// Properties
// -------------------------------------------------------------------------
public ActiveMQDestination getActiveMQDestination() {
return destination;
}


public MessageGroupMap getMessageGroupOwners() {
if (messageGroupOwners == null) {
messageGroupOwners = getMessageGroupMapFactory().createMessageGroupMap();
}
return messageGroupOwners;
}

public DispatchPolicy getDispatchPolicy() {
return dispatchPolicy;
}

public void setDispatchPolicy(DispatchPolicy dispatchPolicy) {
this.dispatchPolicy = dispatchPolicy;
}

public MessageGroupMapFactory getMessageGroupMapFactory() {
return messageGroupMapFactory;
}

public void setMessageGroupMapFactory(MessageGroupMapFactory messageGroupMapFactory) {
this.messageGroupMapFactory = messageGroupMapFactory;
}

public PendingMessageCursor getMessages() {
return this.messages;
}

public void setMessages(PendingMessageCursor messages) {
this.messages = messages;
}

public boolean isUseConsumerPriority() {
return useConsumerPriority;
}

public void setUseConsumerPriority(boolean useConsumerPriority) {
this.useConsumerPriority = useConsumerPriority;
}

public boolean isStrictOrderDispatch() {
return strictOrderDispatch;
}

public void setStrictOrderDispatch(boolean strictOrderDispatch) {
this.strictOrderDispatch = strictOrderDispatch;
}


public boolean isOptimizedDispatch() {
return optimizedDispatch;
}

public void setOptimizedDispatch(boolean optimizedDispatch) {
this.optimizedDispatch = optimizedDispatch;
}
public int getTimeBeforeDispatchStarts() {
return timeBeforeDispatchStarts;
}

public void setTimeBeforeDispatchStarts(int timeBeforeDispatchStarts) {
this.timeBeforeDispatchStarts = timeBeforeDispatchStarts;
}

public int getConsumersBeforeDispatchStarts() {
return consumersBeforeDispatchStarts;
}

public void setConsumersBeforeDispatchStarts(int consumersBeforeDispatchStarts) {
this.consumersBeforeDispatchStarts = consumersBeforeDispatchStarts;
}

// Implementation methods
// -------------------------------------------------------------------------
private QueueMessageReference createMessageReference(Message message) {
QueueMessageReference result = new IndirectMessageReference(message);
return result;
}

public Message[] browse() {
List<Message> l = new ArrayList<Message>();
doBrowse(false, l, getMaxBrowsePageSize());
return l.toArray(new Message[l.size()]);
}

public void doBrowse(boolean forcePageIn, List<Message> l, int max) {
final ConnectionContext connectionContext = createConnectionContext();
try {
pageInMessages(forcePageIn);
List<MessageReference> toExpire = new ArrayList<MessageReference>();
synchronized(dispatchMutex) {
synchronized (pagedInPendingDispatch) {
addAll(pagedInPendingDispatch, l, max, toExpire);
for (MessageReference ref : toExpire) {
pagedInPendingDispatch.remove(ref);
if (broker.isExpired(ref)) {
messageExpired(connectionContext, ref);
}
}
}
toExpire.clear();
synchronized (pagedInMessages) {
addAll(pagedInMessages.values(), l, max, toExpire);
}
for (MessageReference ref : toExpire) {
if (broker.isExpired(ref)) {
messageExpired(connectionContext, ref);
} else {
synchronized (pagedInMessages) {
pagedInMessages.remove(ref.getMessageId());
}
}
}

if (l.size() < getMaxBrowsePageSize()) {
synchronized (messages) {
try {
messages.reset();
while (messages.hasNext() && l.size() < max) {
MessageReference node = messages.next();
messages.rollback(node.getMessageId());
if (node != null) {
if (broker.isExpired(node)) {
messageExpired(connectionContext,
createMessageReference(node.getMessage()));
} else if (l.contains(node.getMessage()) == false) {
l.add(node.getMessage());
}
}
}
} finally {
messages.release();
}
}
}
}
} catch (Exception e) {
LOG.error("Problem retrieving message for browse", e);
}
}

private void addAll(Collection<QueueMessageReference> refs,
List<Message> l, int maxBrowsePageSize, List<MessageReference> toExpire) throws Exception {
for (Iterator<QueueMessageReference> i = refs.iterator(); i.hasNext()
&& l.size() < getMaxBrowsePageSize();) {
QueueMessageReference ref = i.next();
if (ref.isExpired()) {
toExpire.add(ref);
} else if (l.contains(ref.getMessage()) == false) {
l.add(ref.getMessage());
}
}
}

public Message getMessage(String id) {
MessageId msgId = new MessageId(id);
try {
synchronized (pagedInMessages) {
QueueMessageReference r = this.pagedInMessages.get(msgId);
if (r != null) {
return r.getMessage();
}
}
synchronized (messages) {
try {
messages.reset();
while (messages.hasNext()) {
try {
MessageReference r = messages.next();
messages.rollback(r.getMessageId());
if (msgId.equals(r.getMessageId())) {
Message m = r.getMessage();
if (m != null) {
return m;
}
break;
}
} catch (IOException e) {
LOG.error("got an exception retrieving message "
+ id);
}
}
} finally {
messages.release();
}
}
} catch (IOException e) {
LOG.error("got an exception retrieving message " + id);
}
return null;
}

public void purge() throws Exception {
ConnectionContext c = createConnectionContext();
List<MessageReference> list = null;
do {
pageInMessages();
synchronized (pagedInMessages) {
list = new ArrayList<MessageReference>(pagedInMessages.values());
}

for (MessageReference ref : list) {
try {
QueueMessageReference r = (QueueMessageReference) ref;
removeMessage(c,(IndirectMessageReference) r);
} catch (IOException e) {
}
}
} while (!pagedInMessages.isEmpty() || this.destinationStatistics.getMessages().getCount() > 0);
gc();
this.destinationStatistics.getMessages().setCount(0);
getMessages().clear();
}

/**
* Removes the message matching the given messageId
*/
public boolean removeMessage(String messageId) throws Exception {
return removeMatchingMessages(createMessageIdFilter(messageId), 1) > 0;
}

/**
* Removes the messages matching the given selector
*
* @return the number of messages removed
*/
public int removeMatchingMessages(String selector) throws Exception {
return removeMatchingMessages(selector, -1);
}

/**
* Removes the messages matching the given selector up to the maximum number
* of matched messages
*
* @return the number of messages removed
*/
public int removeMatchingMessages(String selector, int maximumMessages) throws Exception {
return removeMatchingMessages(createSelectorFilter(selector), maximumMessages);
}

/**
* Removes the messages matching the given filter up to the maximum number
* of matched messages
*
* @return the number of messages removed
*/
public int removeMatchingMessages(MessageReferenceFilter filter, int maximumMessages) throws Exception {
int movedCounter = 0;
Set<MessageReference> set = new CopyOnWriteArraySet<MessageReference>();
ConnectionContext context = createConnectionContext();
do {
pageInMessages();
synchronized (pagedInMessages) {
set.addAll(pagedInMessages.values());
}
List <MessageReference>list = new ArrayList<MessageReference>(set);
for (MessageReference ref : list) {
IndirectMessageReference r = (IndirectMessageReference) ref;
if (filter.evaluate(context, r)) {

removeMessage(context, r);
set.remove(r);
if (++movedCounter >= maximumMessages
&& maximumMessages > 0) {
return movedCounter;
}
}
}
} while (set.size() < this.destinationStatistics.getMessages().getCount());
return movedCounter;
}

/**
* Copies the message matching the given messageId
*/
public boolean copyMessageTo(ConnectionContext context, String messageId, ActiveMQDestination dest) throws Exception {
return copyMatchingMessages(context, createMessageIdFilter(messageId), dest, 1) > 0;
}

/**
* Copies the messages matching the given selector
*
* @return the number of messages copied
*/
public int copyMatchingMessagesTo(ConnectionContext context, String selector, ActiveMQDestination dest) throws Exception {
return copyMatchingMessagesTo(context, selector, dest, -1);
}

/**
* Copies the messages matching the given selector up to the maximum number
* of matched messages
*
* @return the number of messages copied
*/
public int copyMatchingMessagesTo(ConnectionContext context, String selector, ActiveMQDestination dest, int maximumMessages) throws Exception {
return copyMatchingMessages(context, createSelectorFilter(selector), dest, maximumMessages);
}

/**
* Copies the messages matching the given filter up to the maximum number of
* matched messages
*
* @return the number of messages copied
*/
public int copyMatchingMessages(ConnectionContext context, MessageReferenceFilter filter, ActiveMQDestination dest, int maximumMessages) throws Exception {
int movedCounter = 0;
int count = 0;
Set<MessageReference> set = new CopyOnWriteArraySet<MessageReference>();
do {
int oldMaxSize=getMaxPageSize();
setMaxPageSize((int) this.destinationStatistics.getMessages().getCount());
pageInMessages();
setMaxPageSize(oldMaxSize);
synchronized (pagedInMessages) {
set.addAll(pagedInMessages.values());
}
List <MessageReference>list = new ArrayList<MessageReference>(set);
for (MessageReference ref : list) {
IndirectMessageReference r = (IndirectMessageReference) ref;
if (filter.evaluate(context, r)) {

r.incrementReferenceCount();
try {
Message m = r.getMessage();
BrokerSupport.resend(context, m, dest);
if (++movedCounter >= maximumMessages
&& maximumMessages > 0) {
return movedCounter;
}
} finally {
r.decrementReferenceCount();
}
}
count++;
}
} while (count < this.destinationStatistics.getMessages().getCount());
return movedCounter;
}

/**
* Move a message
* @param context connection context
* @param m message
* @param dest ActiveMQDestination
* @throws Exception
*/
public boolean moveMessageTo(ConnectionContext context,Message m,ActiveMQDestination dest) throws Exception {
QueueMessageReference r = createMessageReference(m);
BrokerSupport.resend(context, m, dest);
removeMessage(context, r);
synchronized (messages) {
messages.rollback(r.getMessageId());
}
return true;
}

/**
* Moves the message matching the given messageId
*/
public boolean moveMessageTo(ConnectionContext context, String messageId, ActiveMQDestination dest) throws Exception {
return moveMatchingMessagesTo(context, createMessageIdFilter(messageId), dest, 1) > 0;
}

/**
* Moves the messages matching the given selector
*
* @return the number of messages removed
*/
public int moveMatchingMessagesTo(ConnectionContext context, String selector, ActiveMQDestination dest) throws Exception {
return moveMatchingMessagesTo(context, selector, dest,Integer.MAX_VALUE);
}

/**
* Moves the messages matching the given selector up to the maximum number
* of matched messages
*/
public int moveMatchingMessagesTo(ConnectionContext context, String selector, ActiveMQDestination dest, int maximumMessages) throws Exception {
return moveMatchingMessagesTo(context, createSelectorFilter(selector), dest, maximumMessages);
}

/**
* Moves the messages matching the given filter up to the maximum number of
* matched messages
*/
public int moveMatchingMessagesTo(ConnectionContext context,
MessageReferenceFilter filter, ActiveMQDestination dest,
int maximumMessages) throws Exception {
int movedCounter = 0;
Set<MessageReference> set = new CopyOnWriteArraySet<MessageReference>();
do {
doPageIn(true);
synchronized (pagedInMessages) {
set.addAll(pagedInMessages.values());
}
List<MessageReference> list = new ArrayList<MessageReference>(set);
for (MessageReference ref : list) {
IndirectMessageReference r = (IndirectMessageReference) ref;
if (filter.evaluate(context, r)) {
// We should only move messages that can be locked.
moveMessageTo(context, ref.getMessage(), dest);
set.remove(r);
if (++movedCounter >= maximumMessages
&& maximumMessages > 0) {
return movedCounter;
}
}
}
} while (set.size() < this.destinationStatistics.getMessages().getCount()
&& set.size() < maximumMessages);
return movedCounter;
}

BrowserDispatch getNextBrowserDispatch() {
synchronized (pagedInMessages) {
if( browserDispatches.isEmpty() ) {
return null;
}
return browserDispatches.removeFirst();
}

}

/**
* @return true if we would like to iterate again
* @see org.apache.activemq.thread.Task#iterate()
*/
public boolean iterate() {
boolean pageInMoreMessages = false;
synchronized(iteratingMutex) {

// do early to allow dispatch of these waiting messages
synchronized(messagesWaitingForSpace) {
while (!messagesWaitingForSpace.isEmpty() && !memoryUsage.isFull()) {
Runnable op = messagesWaitingForSpace.removeFirst();
op.run();
}
}

BrowserDispatch rd;
while ((rd = getNextBrowserDispatch()) != null) {
pageInMoreMessages = true;

try {
MessageEvaluationContext msgContext = new NonCachedMessageEvaluationContext();
msgContext.setDestination(destination);

QueueBrowserSubscription browser = rd.getBrowser();
for (QueueMessageReference node : rd.messages) {
if (!node.isAcked()) {
msgContext.setMessageReference(node);
if (browser.matches(node, msgContext)) {
browser.add(node);
}
}
}

rd.done();

} catch (Exception e) {
LOG.warn("exception on dispatch to browser: " + rd.getBrowser(), e);
}
}

if (firstConsumer) {
firstConsumer = false;
try {
if (consumersBeforeDispatchStarts > 0) {
int timeout = 1000; // wait one second by default if consumer count isn't reached
if (timeBeforeDispatchStarts > 0) {
timeout = timeBeforeDispatchStarts;
}
if (consumersBeforeStartsLatch.await(timeout, TimeUnit.MILLISECONDS)) {
if (LOG.isDebugEnabled()) {
LOG.debug(consumers.size() + " consumers subscribed. Starting dispatch.");
}
} else {
if (LOG.isDebugEnabled()) {
LOG.debug(timeout + " ms elapsed and " +  consumers.size() + " consumers subscribed. Starting dispatch.");
}
}
}
if (timeBeforeDispatchStarts > 0 && consumersBeforeDispatchStarts <= 0) {
iteratingMutex.wait(timeBeforeDispatchStarts);
if (LOG.isDebugEnabled()) {
LOG.debug(timeBeforeDispatchStarts + " ms elapsed. Starting dispatch.");
}
}
} catch (Exception e) {
LOG.error(e);
}
}

synchronized (messages) {
pageInMoreMessages |= !messages.isEmpty();
}

// Kinda ugly.. but I think dispatchLock is the only mutex protecting the
// pagedInPendingDispatch variable.
synchronized(dispatchMutex) {
pageInMoreMessages |= !pagedInPendingDispatch.isEmpty();
}

// Perhaps we should page always into the pagedInPendingDispatch list is
// !messages.isEmpty(), and then if !pagedInPendingDispatch.isEmpty()
// then we do a dispatch.
if (pageInMoreMessages) {
try {
pageInMessages(false);

} catch (Throwable e) {
LOG.error("Failed to page in more queue messages ", e);
}
}
return !messagesWaitingForSpace.isEmpty();
}
}

protected MessageReferenceFilter createMessageIdFilter(final String messageId) {
return new MessageReferenceFilter() {
public boolean evaluate(ConnectionContext context, MessageReference r) {
return messageId.equals(r.getMessageId().toString());
}
public String toString() {
return "MessageIdFilter: "+messageId;
}
};
}

protected MessageReferenceFilter createSelectorFilter(String selector) throws InvalidSelectorException {
final BooleanExpression selectorExpression = SelectorParser.parse(selector);

return new MessageReferenceFilter() {
public boolean evaluate(ConnectionContext context, MessageReference r) throws JMSException {
MessageEvaluationContext messageEvaluationContext = context.getMessageEvaluationContext();

messageEvaluationContext.setMessageReference(r);
if (messageEvaluationContext.getDestination() == null) {
messageEvaluationContext.setDestination(getActiveMQDestination());
}

return selectorExpression.matches(messageEvaluationContext);
}
};
}

protected void removeMessage(ConnectionContext c, QueueMessageReference r) throws IOException {
removeMessage(c, null, r);
}

protected void removeMessage(ConnectionContext c, Subscription subs,QueueMessageReference r) throws IOException {
MessageAck ack = new MessageAck();
ack.setAckType(MessageAck.STANDARD_ACK_TYPE);
ack.setDestination(destination);
ack.setMessageID(r.getMessageId());
removeMessage(c, subs, r, ack);
}

protected void removeMessage(ConnectionContext context,Subscription sub,final QueueMessageReference reference,MessageAck ack) throws IOException {
reference.setAcked(true);
// This sends the ack the the journal..
if (!ack.isInTransaction()) {
acknowledge(context, sub, ack, reference);
getDestinationStatistics().getDequeues().increment();
dropMessage(reference);
} else {
try {
acknowledge(context, sub, ack, reference);
} finally {
context.getTransaction().addSynchronization(new Synchronization() {

public void afterCommit() throws Exception {
getDestinationStatistics().getDequeues().increment();
dropMessage(reference);
wakeup();
}

public void afterRollback() throws Exception {
reference.setAcked(false);
}
});
}
}
if (ack.isPoisonAck()) {
// message gone to DLQ, is ok to allow redelivery
synchronized(messages) {
messages.rollback(reference.getMessageId());
}
}

}

private void dropMessage(QueueMessageReference reference) {
reference.drop();
destinationStatistics.getMessages().decrement();
synchronized(pagedInMessages) {
pagedInMessages.remove(reference.getMessageId());
}
}

public void messageExpired(ConnectionContext context,MessageReference reference) {
messageExpired(context,null,reference);
}

public void messageExpired(ConnectionContext context,Subscription subs, MessageReference reference) {
if (LOG.isDebugEnabled()) {
LOG.debug("message expired: " + reference);
}
broker.messageExpired(context, reference);
destinationStatistics.getExpired().increment();
try {
removeMessage(context,subs,(QueueMessageReference)reference);
} catch (IOException e) {
LOG.error("Failed to remove expired Message from the store ",e);
}
asyncWakeup();
}

protected ConnectionContext createConnectionContext() {
ConnectionContext answer = new ConnectionContext(new NonCachedMessageEvaluationContext());
answer.setBroker(this.broker);
answer.getMessageEvaluationContext().setDestination(getActiveMQDestination());
return answer;
}

final void sendMessage(final ConnectionContext context, Message msg) throws Exception {
if (!msg.isPersistent() && messages.getSystemUsage() != null) {
if (systemUsage.getTempUsage().isFull()) {
final String logMessage = "Usage Manager Temp Store is Full. Stopping producer (" + msg.getProducerId() + ") to prevent flooding " + getActiveMQDestination().getQualifiedName() + "." +
" See http://activemq.apache.org/producer-flow-control.html for more info";
LOG.info(logMessage);
if (systemUsage.isSendFailIfNoSpace()) {
throw new javax.jms.ResourceAllocationException(logMessage);
}
}
messages.getSystemUsage().getTempUsage().waitForSpace();
}
synchronized(messages) {
messages.addMessageLast(msg);
}
destinationStatistics.getEnqueues().increment();
destinationStatistics.getMessages().increment();
messageDelivered(context, msg);
synchronized (consumers) {
if (consumers.isEmpty()) {
onMessageWithNoConsumers(context, msg);
}
}
wakeup();
}

public void wakeup() {
if (optimizedDispatch || isSlave()) {
iterate();
} else {
asyncWakeup();
}
}

public void asyncWakeup() {
try {
this.taskRunner.wakeup();
} catch (InterruptedException e) {
LOG.warn("Async task tunner failed to wakeup ", e);
}
}

private boolean isSlave() {
return broker.getBrokerService().isSlave();
}

private List<QueueMessageReference> doPageIn(boolean force) throws Exception {
List<QueueMessageReference> result = null;
List<QueueMessageReference> resultList = null;
synchronized(dispatchMutex) {
int toPageIn = Math.min(getMaxPageSize(), messages.size());
if (LOG.isDebugEnabled()) {
LOG.debug(destination.getPhysicalName() + " toPageIn: "  + toPageIn + ", Inflight: "
+ destinationStatistics.getInflight().getCount()
+ ", pagedInMessages.size " + pagedInMessages.size());
}

if (isLazyDispatch()&& !force) {
// Only page in the minimum number of messages which can be dispatched immediately.
toPageIn = Math.min(getConsumerMessageCountBeforeFull(), toPageIn);
}

if ((force || !consumers.isEmpty()) && toPageIn > 0) {
int count = 0;
result = new ArrayList<QueueMessageReference>(toPageIn);
synchronized (messages) {
try {
messages.setMaxBatchSize(toPageIn);
messages.reset();
while (messages.hasNext() && count < toPageIn) {
MessageReference node = messages.next();
node.incrementReferenceCount();
messages.remove();
QueueMessageReference ref = createMessageReference(node.getMessage());
if (!broker.isExpired(node)) {
result.add(ref);
count++;
} else {
messageExpired(createConnectionContext(), ref);
}
}
} finally {
messages.release();
}
}
// Only add new messages, not already pagedIn to avoid multiple dispatch attempts
synchronized (pagedInMessages) {
resultList = new ArrayList<QueueMessageReference>(result.size());
for(QueueMessageReference ref : result) {
if (!pagedInMessages.containsKey(ref.getMessageId())) {
pagedInMessages.put(ref.getMessageId(), ref);
resultList.add(ref);
}
}
}
} else {
// Avoid return null list, if condition is not validated
resultList = new ArrayList<QueueMessageReference>();
}
}
return resultList;
}

private void doDispatch(List<QueueMessageReference> list) throws Exception {
boolean doWakeUp = false;
synchronized(dispatchMutex) {

synchronized (pagedInPendingDispatch) {
if (!pagedInPendingDispatch.isEmpty()) {
// Try to first dispatch anything that had not been
// dispatched before.
pagedInPendingDispatch = doActualDispatch(pagedInPendingDispatch);
}
// and now see if we can dispatch the new stuff.. and append to
// the pending
// list anything that does not actually get dispatched.
if (list != null && !list.isEmpty()) {
if (pagedInPendingDispatch.isEmpty()) {
pagedInPendingDispatch.addAll(doActualDispatch(list));
} else {
for (QueueMessageReference qmr : list) {
if (!pagedInPendingDispatch.contains(qmr)) {
pagedInPendingDispatch.add(qmr);
}
}
doWakeUp  = true;
}
}
}
}
if (doWakeUp) {
wakeup();
}
}

/**
* @return list of messages that could get dispatched to consumers if they
*         were not full.
*/
private List<QueueMessageReference> doActualDispatch(List<QueueMessageReference> list) throws Exception {
List<Subscription> consumers;

synchronized (this.consumers) {
if (this.consumers.isEmpty() || isSlave()) {
// slave dispatch happens in processDispatchNotification
return list;
}
consumers = new ArrayList<Subscription>(this.consumers);
}

List<QueueMessageReference> rc = new ArrayList<QueueMessageReference>(list.size());
Set<Subscription> fullConsumers = new HashSet<Subscription>(this.consumers.size());

for (MessageReference node : list) {
Subscription target = null;
int interestCount=0;
for (Subscription s : consumers) {
if (s instanceof QueueBrowserSubscription) {
interestCount++;
continue;
}
if (dispatchSelector.canSelect(s, node)) {
if (!fullConsumers.contains(s)) {
if (!s.isFull()) {
// Dispatch it.
s.add(node);
target = s;
break;
} else {
// no further dispatch of list to a full consumer to avoid out of order message receipt
fullConsumers.add(s);
}
}
interestCount++;
} else {
// makes sure it gets dispatched again
if (!node.isDropped() && !((QueueMessageReference)node).isAcked() && (!node.isDropped() || s.getConsumerInfo().isBrowser())) {
interestCount++;
}
}
}

if ((target == null && interestCount>0) || consumers.size() == 0) {
// This means all subs were full or that there are no consumers...
rc.add((QueueMessageReference)node);
}

// If it got dispatched, rotate the consumer list to get round robin distribution.
if (target != null && !strictOrderDispatch && consumers.size() > 1 &&
!dispatchSelector.isExclusiveConsumer(target)) {
synchronized (this.consumers) {
if( removeFromConsumerList(target) ) {
addToConsumerList(target);
consumers = new ArrayList<Subscription>(this.consumers);
}
}
}
}


return rc;
}

private void pageInMessages() throws Exception {
pageInMessages(true);
}

protected void pageInMessages(boolean force) throws Exception {
doDispatch(doPageIn(force));
}

private void addToConsumerList(Subscription sub) {
if (useConsumerPriority) {
consumers.add(sub);
Collections.sort(consumers, orderedCompare);
} else {
consumers.add(sub);
}
}

private boolean removeFromConsumerList(Subscription sub) {
return consumers.remove(sub);
}

private int getConsumerMessageCountBeforeFull() throws Exception {
int total = 0;
boolean zeroPrefetch = false;
synchronized (consumers) {
for (Subscription s : consumers) {
zeroPrefetch |= s.getPrefetchSize() == 0;
int countBeforeFull = s.countBeforeFull();
total += countBeforeFull;
}
}
if (total==0 && zeroPrefetch){
total=1;
}
return total;
}

/*
* In slave mode, dispatch is ignored till we get this notification as the dispatch
* process is non deterministic between master and slave.
* On a notification, the actual dispatch to the subscription (as chosen by the master)
* is completed.
* (non-Javadoc)
* @see org.apache.activemq.broker.region.BaseDestination#processDispatchNotification(org.apache.activemq.command.MessageDispatchNotification)
*/
public void processDispatchNotification(
MessageDispatchNotification messageDispatchNotification) throws Exception {
// do dispatch
Subscription sub = getMatchingSubscription(messageDispatchNotification);
if (sub != null) {
MessageReference message = getMatchingMessage(messageDispatchNotification);
sub.add(message);
sub.processMessageDispatchNotification(messageDispatchNotification);
}
}

private QueueMessageReference getMatchingMessage(MessageDispatchNotification messageDispatchNotification) throws Exception {
QueueMessageReference message = null;
MessageId messageId = messageDispatchNotification.getMessageId();

synchronized(dispatchMutex) {
synchronized (pagedInPendingDispatch) {
for(QueueMessageReference ref : pagedInPendingDispatch) {
if (messageId.equals(ref.getMessageId())) {
message = ref;
pagedInPendingDispatch.remove(ref);
break;
}
}
}

if (message == null) {
synchronized (pagedInMessages) {
message = pagedInMessages.get(messageId);
}
}

if (message == null) {
synchronized (messages) {
try {
messages.setMaxBatchSize(getMaxPageSize());
messages.reset();
while (messages.hasNext()) {
MessageReference node = messages.next();
node.incrementReferenceCount();
messages.remove();
if (messageId.equals(node.getMessageId())) {
message = this.createMessageReference(node.getMessage());
break;
}
}
} finally {
messages.release();
}
}
}

if (message == null) {
Message msg = loadMessage(messageId);
if (msg != null) {
message = this.createMessageReference(msg);
}
}

}
if (message == null) {
throw new JMSException(
"Slave broker out of sync with master - Message: "
+ messageDispatchNotification.getMessageId()
+ " on " + messageDispatchNotification.getDestination()
+ " does not exist among pending(" + pagedInPendingDispatch.size() + ") for subscription: "
+ messageDispatchNotification.getConsumerId());
}
return message;
}

/**
* Find a consumer that matches the id in the message dispatch notification
* @param messageDispatchNotification
* @return sub or null if the subscription has been removed before dispatch
* @throws JMSException
*/
private Subscription getMatchingSubscription(MessageDispatchNotification messageDispatchNotification) throws JMSException {
Subscription sub = null;
synchronized (consumers) {
for (Subscription s : consumers) {
if (messageDispatchNotification.getConsumerId().equals(s.getConsumerInfo().getConsumerId())) {
sub = s;
break;
}
}
}
return sub;
}

public void onUsageChanged(Usage usage, int oldPercentUsage, int newPercentUsage) {
if (oldPercentUsage > newPercentUsage) {
asyncWakeup();
}
}
}