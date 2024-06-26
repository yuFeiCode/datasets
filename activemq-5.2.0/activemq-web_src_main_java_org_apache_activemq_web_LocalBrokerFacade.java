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
package org.apache.activemq.web;

import java.util.Iterator;
import java.util.Set;

import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.jmx.BrokerView;
import org.apache.activemq.broker.jmx.BrokerViewMBean;
import org.apache.activemq.broker.jmx.ManagedRegionBroker;
import org.apache.activemq.broker.jmx.ManagementContext;
import org.apache.activemq.broker.region.Queue;
import org.apache.activemq.command.ActiveMQDestination;

/**
* An implementation of {@link BrokerFacade} which uses a local in JVM broker
*
* @version $Revision$
*/
public class LocalBrokerFacade extends BrokerFacadeSupport {
private BrokerService brokerService;

public LocalBrokerFacade(BrokerService brokerService) {
this.brokerService = brokerService;
}

public BrokerService getBrokerService() {
return brokerService;
}

public Broker getBroker() throws Exception {
return brokerService.getBroker();
}

public ManagementContext getManagementContext() {
return brokerService.getManagementContext();
}

public BrokerViewMBean getBrokerAdmin() throws Exception {
// TODO could use JMX to look this up
return brokerService.getAdminView();
}

public ManagedRegionBroker getManagedBroker() throws Exception {
BrokerView adminView = brokerService.getAdminView();
if (adminView == null) {
return null;
}
return adminView.getBroker();
}

public void purgeQueue(ActiveMQDestination destination) throws Exception {
Set destinations = getManagedBroker().getQueueRegion().getDestinations(destination);
for (Iterator i = destinations.iterator(); i.hasNext();) {
Queue regionQueue = (Queue)i.next();
regionQueue.purge();
}
}
}