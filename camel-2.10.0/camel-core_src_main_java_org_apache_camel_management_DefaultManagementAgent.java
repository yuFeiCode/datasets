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
package org.apache.camel.management;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.spi.ManagementAgent;
import org.apache.camel.spi.ManagementMBeanAssembler;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* Default implementation of the Camel JMX service agent
*/
public class DefaultManagementAgent extends ServiceSupport implements ManagementAgent, CamelContextAware {

public static final String DEFAULT_DOMAIN = "org.apache.camel";
public static final String DEFAULT_HOST = "localhost";
public static final int DEFAULT_REGISTRY_PORT = 1099;
public static final int DEFAULT_CONNECTION_PORT = -1;
public static final String DEFAULT_SERVICE_URL_PATH = "/jmxrmi/camel";
private static final transient Logger LOG = LoggerFactory.getLogger(DefaultManagementAgent.class);

private CamelContext camelContext;
private ExecutorService executorService;
private MBeanServer server;
// need a name -> actual name mapping as some servers changes the names (suc as WebSphere)
private final Map<ObjectName, ObjectName> mbeansRegistered = new HashMap<ObjectName, ObjectName>();
private JMXConnectorServer cs;

private Integer registryPort;
private Integer connectorPort;
private String mBeanServerDefaultDomain;
private String mBeanObjectDomainName;
private String serviceUrlPath;
private Boolean usePlatformMBeanServer = true;
private Boolean createConnector;
private Boolean onlyRegisterProcessorWithCustomId;
private Boolean registerAlways;
private Boolean registerNewRoutes = true;

public DefaultManagementAgent() {
}

public DefaultManagementAgent(CamelContext camelContext) {
this.camelContext = camelContext;
}

protected void finalizeSettings() {
// TODO: System properties ought to take precedence, over configured options

if (registryPort == null) {
registryPort = Integer.getInteger(JmxSystemPropertyKeys.REGISTRY_PORT, DEFAULT_REGISTRY_PORT);
}
if (connectorPort == null) {
connectorPort = Integer.getInteger(JmxSystemPropertyKeys.CONNECTOR_PORT, DEFAULT_CONNECTION_PORT);
}
if (mBeanServerDefaultDomain == null) {
mBeanServerDefaultDomain = System.getProperty(JmxSystemPropertyKeys.DOMAIN, DEFAULT_DOMAIN);
}
if (mBeanObjectDomainName == null) {
mBeanObjectDomainName = System.getProperty(JmxSystemPropertyKeys.MBEAN_DOMAIN, DEFAULT_DOMAIN);
}
if (serviceUrlPath == null) {
serviceUrlPath = System.getProperty(JmxSystemPropertyKeys.SERVICE_URL_PATH, DEFAULT_SERVICE_URL_PATH);
}
if (createConnector == null) {
createConnector = Boolean.getBoolean(JmxSystemPropertyKeys.CREATE_CONNECTOR);
}
if (onlyRegisterProcessorWithCustomId == null) {
onlyRegisterProcessorWithCustomId = Boolean.getBoolean(JmxSystemPropertyKeys.ONLY_REGISTER_PROCESSOR_WITH_CUSTOM_ID);
}
// "Use platform mbean server" is true by default
if (System.getProperty(JmxSystemPropertyKeys.USE_PLATFORM_MBS) != null) {
usePlatformMBeanServer = Boolean.getBoolean(JmxSystemPropertyKeys.USE_PLATFORM_MBS);
}

if (System.getProperty(JmxSystemPropertyKeys.REGISTER_ALWAYS) != null) {
registerAlways = Boolean.getBoolean(JmxSystemPropertyKeys.REGISTER_ALWAYS);
}
if (System.getProperty(JmxSystemPropertyKeys.REGISTER_NEW_ROUTES) != null) {
registerNewRoutes = Boolean.getBoolean(JmxSystemPropertyKeys.REGISTER_NEW_ROUTES);
}
}

public void setRegistryPort(Integer port) {
registryPort = port;
}

public Integer getRegistryPort() {
return registryPort;
}

public void setConnectorPort(Integer port) {
connectorPort = port;
}

public Integer getConnectorPort() {
return connectorPort;
}

public void setMBeanServerDefaultDomain(String domain) {
mBeanServerDefaultDomain = domain;
}

public String getMBeanServerDefaultDomain() {
return mBeanServerDefaultDomain;
}

public void setMBeanObjectDomainName(String domainName) {
mBeanObjectDomainName = domainName;
}

public String getMBeanObjectDomainName() {
return mBeanObjectDomainName;
}

public void setServiceUrlPath(String url) {
serviceUrlPath = url;
}

public String getServiceUrlPath() {
return serviceUrlPath;
}

public void setCreateConnector(Boolean flag) {
createConnector = flag;
}

public Boolean getCreateConnector() {
return createConnector;
}

public void setUsePlatformMBeanServer(Boolean flag) {
usePlatformMBeanServer = flag;
}

public Boolean getUsePlatformMBeanServer() {
return usePlatformMBeanServer;
}

public Boolean getOnlyRegisterProcessorWithCustomId() {
return onlyRegisterProcessorWithCustomId;
}

public void setOnlyRegisterProcessorWithCustomId(Boolean onlyRegisterProcessorWithCustomId) {
this.onlyRegisterProcessorWithCustomId = onlyRegisterProcessorWithCustomId;
}

public void setMBeanServer(MBeanServer mbeanServer) {
server = mbeanServer;
}

public MBeanServer getMBeanServer() {
return server;
}

public Boolean getRegisterAlways() {
return registerAlways != null && registerAlways;
}

public void setRegisterAlways(Boolean registerAlways) {
this.registerAlways = registerAlways;
}

public Boolean getRegisterNewRoutes() {
return registerNewRoutes != null && registerNewRoutes;
}

public void setRegisterNewRoutes(Boolean registerNewRoutes) {
this.registerNewRoutes = registerNewRoutes;
}

public ExecutorService getExecutorService() {
return executorService;
}

public void setExecutorService(ExecutorService executorService) {
this.executorService = executorService;
}

public CamelContext getCamelContext() {
return camelContext;
}

public void setCamelContext(CamelContext camelContext) {
this.camelContext = camelContext;
}

public void register(Object obj, ObjectName name) throws JMException {
register(obj, name, false);
}

public void register(Object obj, ObjectName name, boolean forceRegistration) throws JMException {
try {
registerMBeanWithServer(obj, name, forceRegistration);
} catch (NotCompliantMBeanException e) {
// If this is not a "normal" MBean, then try to deploy it using JMX annotations
ManagementMBeanAssembler assembler = camelContext.getManagementMBeanAssembler();
ObjectHelper.notNull(assembler, "ManagementMBeanAssembler", camelContext);
Object mbean = assembler.assemble(server, obj, name);
// and register the mbean
registerMBeanWithServer(mbean, name, forceRegistration);
}
}

public void unregister(ObjectName name) throws JMException {
if (isRegistered(name)) {
server.unregisterMBean(mbeansRegistered.get(name));
LOG.debug("Unregistered MBean with ObjectName: {}", name);
}
mbeansRegistered.remove(name);
}

public boolean isRegistered(ObjectName name) {
return (mbeansRegistered.containsKey(name)
&& server.isRegistered(mbeansRegistered.get(name)))
|| server.isRegistered(name);
}

protected void doStart() throws Exception {
ObjectHelper.notNull(camelContext, "CamelContext");

// create mbean server if is has not be injected.
if (server == null) {
finalizeSettings();
createMBeanServer();
}

LOG.debug("Starting JMX agent on server: {}", getMBeanServer());
}

protected void doStop() throws Exception {
// close JMX Connector
if (cs != null) {
try {
cs.stop();
} catch (IOException e) {
LOG.debug("Error occurred during stopping JMXConnectorService: "
+ cs + ". This exception will be ignored.");
}
cs = null;
}

if (mbeansRegistered.isEmpty()) {
return;
}

// Using the array to hold the busMBeans to avoid the CurrentModificationException
ObjectName[] mBeans = mbeansRegistered.keySet().toArray(new ObjectName[mbeansRegistered.size()]);
int caught = 0;
for (ObjectName name : mBeans) {
try {
unregister(name);
} catch (Exception e) {
LOG.info("Exception unregistering MBean with name " + name, e);
caught++;
}
}
if (caught > 0) {
LOG.warn("A number of " + caught
+ " exceptions caught while unregistering MBeans during stop operation."
+ " See INFO log for details.");
}
}

private void registerMBeanWithServer(Object obj, ObjectName name, boolean forceRegistration)
throws JMException {

// have we already registered the bean, there can be shared instances in the camel routes
boolean exists = isRegistered(name);
if (exists) {
if (forceRegistration) {
LOG.info("ForceRegistration enabled, unregistering existing MBean with ObjectName: {}", name);
server.unregisterMBean(name);
} else {
// okay ignore we do not want to force it and it could be a shared instance
LOG.debug("MBean already registered with ObjectName: {}", name);
}
}

// register bean if by force or not exists
ObjectInstance instance = null;
if (forceRegistration || !exists) {
LOG.trace("Registering MBean with ObjectName: {}", name);
instance = server.registerMBean(obj, name);
}

// need to use the name returned from the server as some JEE servers may modify the name
if (instance != null) {
ObjectName registeredName = instance.getObjectName();
LOG.debug("Registered MBean with ObjectName: {}", registeredName);
mbeansRegistered.put(name, registeredName);
}
}

protected void createMBeanServer() {
String hostName;
boolean canAccessSystemProps = true;
try {
// we'll do it this way mostly to determine if we should lookup the hostName
SecurityManager sm = System.getSecurityManager();
if (sm != null) {
sm.checkPropertiesAccess();
}
} catch (SecurityException se) {
canAccessSystemProps = false;
}

if (canAccessSystemProps) {
try {
hostName = InetAddress.getLocalHost().getHostName();
} catch (UnknownHostException uhe) {
LOG.info("Cannot determine localhost name. Using default: " + DEFAULT_REGISTRY_PORT, uhe);
hostName = DEFAULT_HOST;
}
} else {
hostName = DEFAULT_HOST;
}

server = findOrCreateMBeanServer();

try {
// Create the connector if we need
if (createConnector) {
createJmxConnector(hostName);
}
} catch (IOException ioe) {
LOG.warn("Could not create and start JMX connector.", ioe);
}
}

protected MBeanServer findOrCreateMBeanServer() {

// return platform mbean server if the option is specified.
if (usePlatformMBeanServer) {
return ManagementFactory.getPlatformMBeanServer();
}

// look for the first mbean server that has match default domain name
List<MBeanServer> servers = MBeanServerFactory.findMBeanServer(null);

for (MBeanServer server : servers) {
LOG.debug("Found MBeanServer with default domain {}", server.getDefaultDomain());

if (mBeanServerDefaultDomain.equals(server.getDefaultDomain())) {
return server;
}
}

// create a mbean server with the given default domain name
return MBeanServerFactory.createMBeanServer(mBeanServerDefaultDomain);
}

protected void createJmxConnector(String host) throws IOException {
ObjectHelper.notEmpty(serviceUrlPath, "serviceUrlPath");
ObjectHelper.notNull(registryPort, "registryPort");

try {
LocateRegistry.createRegistry(registryPort);
LOG.debug("Created JMXConnector RMI registry on port {}", registryPort);
} catch (RemoteException ex) {
// The registry may had been created, we could get the registry instead
}

// must start with leading slash
String path = serviceUrlPath.startsWith("/") ? serviceUrlPath : "/" + serviceUrlPath;
// Create an RMI connector and start it
final JMXServiceURL url;
if (connectorPort > 0) {
url = new JMXServiceURL("service:jmx:rmi://" + host + ":" + connectorPort + "/jndi/rmi://" + host
+ ":" + registryPort + path);
} else {
url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + ":" + registryPort + path);
}

cs = JMXConnectorServerFactory.newJMXConnectorServer(url, null, server);

if (executorService == null) {
// we only need a single thread for the JMX connector
executorService = camelContext.getExecutorServiceManager().newSingleThreadExecutor(this, "JMXConnector: " + url);
}

// execute the JMX connector
executorService.execute(new Runnable() {
public void run() {
try {
cs.start();
} catch (IOException ioe) {
LOG.warn("Could not start JMXConnector thread.", ioe);
}
}
});

LOG.info("JMX Connector thread started and listening at: " + url);
}

}