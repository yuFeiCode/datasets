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
package org.apache.activemq.store.jdbc;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.AbstractMessageStore;
import org.apache.activemq.usage.MemoryUsage;
import org.apache.activemq.util.ByteSequence;
import org.apache.activemq.util.ByteSequenceData;
import org.apache.activemq.util.IOExceptionSupport;
import org.apache.activemq.wireformat.WireFormat;

/**
* @version $Revision: 1.10 $
*/
public class JDBCMessageStore extends AbstractMessageStore {

protected final WireFormat wireFormat;
protected final JDBCAdapter adapter;
protected final JDBCPersistenceAdapter persistenceAdapter;
protected AtomicLong lastMessageId = new AtomicLong(-1);

public JDBCMessageStore(JDBCPersistenceAdapter persistenceAdapter, JDBCAdapter adapter, WireFormat wireFormat, ActiveMQDestination destination) {
super(destination);
this.persistenceAdapter = persistenceAdapter;
this.adapter = adapter;
this.wireFormat = wireFormat;
}

public void addMessage(ConnectionContext context, Message message) throws IOException {

// Serialize the Message..
byte data[];
try {
ByteSequence packet = wireFormat.marshal(message);
data = ByteSequenceData.toByteArray(packet);
} catch (IOException e) {
throw IOExceptionSupport.create("Failed to broker message: " + message.getMessageId() + " in container: " + e, e);
}

// Get a connection and insert the message into the DB.
TransactionContext c = persistenceAdapter.getTransactionContext(context);
try {
adapter.doAddMessage(c, message.getMessageId(), destination, data, message.getExpiration());
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker message: " + message.getMessageId() + " in container: " + e, e);
} finally {
c.close();
}
}

public void addMessageReference(ConnectionContext context, MessageId messageId, long expirationTime, String messageRef) throws IOException {
// Get a connection and insert the message into the DB.
TransactionContext c = persistenceAdapter.getTransactionContext(context);
try {
adapter.doAddMessageReference(c, messageId, destination, expirationTime, messageRef);
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker message: " + messageId + " in container: " + e, e);
} finally {
c.close();
}
}

public Message getMessage(MessageId messageId) throws IOException {

long id = messageId.getBrokerSequenceId();

// Get a connection and pull the message out of the DB
TransactionContext c = persistenceAdapter.getTransactionContext();
try {
byte data[] = adapter.doGetMessage(c, id);
if (data == null) {
return null;
}

Message answer = (Message)wireFormat.unmarshal(new ByteSequence(data));
return answer;
} catch (IOException e) {
throw IOExceptionSupport.create("Failed to broker message: " + messageId + " in container: " + e, e);
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker message: " + messageId + " in container: " + e, e);
} finally {
c.close();
}
}

public String getMessageReference(MessageId messageId) throws IOException {
long id = messageId.getBrokerSequenceId();

// Get a connection and pull the message out of the DB
TransactionContext c = persistenceAdapter.getTransactionContext();
try {
return adapter.doGetMessageReference(c, id);
} catch (IOException e) {
throw IOExceptionSupport.create("Failed to broker message: " + messageId + " in container: " + e, e);
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker message: " + messageId + " in container: " + e, e);
} finally {
c.close();
}
}

public void removeMessage(ConnectionContext context, MessageAck ack) throws IOException {
long seq = ack.getLastMessageId().getBrokerSequenceId();

// Get a connection and remove the message from the DB
TransactionContext c = persistenceAdapter.getTransactionContext(context);
try {
adapter.doRemoveMessage(c, seq);
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker message: " + ack.getLastMessageId() + " in container: " + e, e);
} finally {
c.close();
}
}

public void recover(final MessageRecoveryListener listener) throws Exception {

// Get all the Message ids out of the database.
TransactionContext c = persistenceAdapter.getTransactionContext();
try {
c = persistenceAdapter.getTransactionContext();
adapter.doRecover(c, destination, new JDBCMessageRecoveryListener() {
public boolean recoverMessage(long sequenceId, byte[] data) throws Exception {
Message msg = (Message)wireFormat.unmarshal(new ByteSequence(data));
msg.getMessageId().setBrokerSequenceId(sequenceId);
return listener.recoverMessage(msg);
}

public boolean recoverMessageReference(String reference) throws Exception {
return listener.recoverMessageReference(new MessageId(reference));
}
});
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to recover container. Reason: " + e, e);
} finally {
c.close();
}
}

/**
* @see org.apache.activemq.store.MessageStore#removeAllMessages(ConnectionContext)
*/
public void removeAllMessages(ConnectionContext context) throws IOException {
// Get a connection and remove the message from the DB
TransactionContext c = persistenceAdapter.getTransactionContext(context);
try {
adapter.doRemoveAllMessages(c, destination);
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to broker remove all messages: " + e, e);
} finally {
c.close();
}
}

public int getMessageCount() throws IOException {
int result = 0;
TransactionContext c = persistenceAdapter.getTransactionContext();
try {

result = adapter.doGetMessageCount(c, destination);

} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
throw IOExceptionSupport.create("Failed to get Message Count: " + destination + ". Reason: " + e, e);
} finally {
c.close();
}
return result;
}

/**
* @param maxReturned
* @param listener
* @throws Exception
* @see org.apache.activemq.store.MessageStore#recoverNextMessages(int,
*      org.apache.activemq.store.MessageRecoveryListener)
*/
public void recoverNextMessages(int maxReturned, final MessageRecoveryListener listener) throws Exception {
TransactionContext c = persistenceAdapter.getTransactionContext();

try {
adapter.doRecoverNextMessages(c, destination, lastMessageId.get(), maxReturned, new JDBCMessageRecoveryListener() {

public boolean recoverMessage(long sequenceId, byte[] data) throws Exception {
if (listener.hasSpace()) {
Message msg = (Message)wireFormat.unmarshal(new ByteSequence(data));
msg.getMessageId().setBrokerSequenceId(sequenceId);
listener.recoverMessage(msg);
lastMessageId.set(sequenceId);
return true;
}
return false;
}

public boolean recoverMessageReference(String reference) throws Exception {
if (listener.hasSpace()) {
listener.recoverMessageReference(new MessageId(reference));
return true;
}
return false;
}

});
} catch (SQLException e) {
JDBCPersistenceAdapter.log("JDBC Failure: ", e);
} finally {
c.close();
}

}

/**
* @see org.apache.activemq.store.MessageStore#resetBatching()
*/
public void resetBatching() {
lastMessageId.set(-1);

}

@Override
public void setBatch(MessageId messageId) {
lastMessageId.set(messageId.getBrokerSequenceId());
}

}