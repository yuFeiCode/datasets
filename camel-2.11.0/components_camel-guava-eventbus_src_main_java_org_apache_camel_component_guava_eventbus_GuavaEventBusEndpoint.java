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
package org.apache.camel.component.guava.eventbus;

import com.google.common.eventbus.EventBus;
import org.apache.camel.Component;
import org.apache.camel.Consumer;
import org.apache.camel.Exchange;
import org.apache.camel.MultipleConsumersSupport;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultEndpoint;

/**
* Guava EventBus (http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/eventbus/EventBus.html)
* endpoint. Can create both producer and consumer ends of the route.
*/
public class GuavaEventBusEndpoint extends DefaultEndpoint implements MultipleConsumersSupport {

private EventBus eventBus;
private Class<?> eventClass;
private Class<?> listenerInterface;

public GuavaEventBusEndpoint(String endpointUri, Component component, EventBus eventBus, Class<?> listenerInterface) {
super(endpointUri, component);
this.eventBus = eventBus;
this.listenerInterface = listenerInterface;
}

@Override
public Producer createProducer() throws Exception {
return new GuavaEventBusProducer(this, eventBus);
}

@Override
public Consumer createConsumer(Processor processor) throws Exception {
return new GuavaEventBusConsumer(this, processor, eventBus, eventClass, listenerInterface);
}

@Override
public boolean isSingleton() {
return true;
}

@Override
public boolean isMultipleConsumersSupported() {
return true;
}

public Exchange createExchange(Object event) {
Exchange exchange = createExchange();
exchange.getIn().setBody(event);
return exchange;
}

public EventBus getEventBus() {
return eventBus;
}

public void setEventBus(EventBus eventBus) {
this.eventBus = eventBus;
}

public Class<?> getEventClass() {
return eventClass;
}

public void setEventClass(Class<?> eventClass) {
this.eventClass = eventClass;
}

public Class<?> getListenerInterface() {
return listenerInterface;
}

public void setListenerInterface(Class<?> listenerInterface) {
this.listenerInterface = listenerInterface;
}

}