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
package org.apache.activemq.transport.tcp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerServiceAware;
import org.apache.activemq.broker.SslContext;
import org.apache.activemq.openwire.OpenWireFormat;
import org.apache.activemq.transport.InactivityMonitor;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.transport.TransportFactory;
import org.apache.activemq.transport.TransportLoggerFactory;
import org.apache.activemq.transport.TransportServer;
import org.apache.activemq.transport.WireFormatNegotiator;
import org.apache.activemq.util.IOExceptionSupport;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.URISupport;
import org.apache.activemq.wireformat.WireFormat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
* An implementation of the TcpTransportFactory using SSL. The major
* contribution from this class is that it is aware of SslTransportServer and
* SslTransport classes. All Transports and TransportServers created from this
* factory will have their needClientAuth option set to false.
*
* @author sepandm@gmail.com (Sepand)
* @author David Martin Clavo david(dot)martin(dot)clavo(at)gmail.com (logging improvement modifications)
* @version $Revision$
*/
public class SslTransportFactory extends TcpTransportFactory {
// The log this uses.,
private static final Log LOG = LogFactory.getLog(SslTransportFactory.class);

/**
* Overriding to use SslTransportServer and allow for proper reflection.
*/
public TransportServer doBind(final URI location) throws IOException {
try {
Map<String, String> options = new HashMap<String, String>(URISupport.parseParamters(location));

ServerSocketFactory serverSocketFactory = createServerSocketFactory();
SslTransportServer server = new SslTransportServer(this, location, (SSLServerSocketFactory)serverSocketFactory);
server.setWireFormatFactory(createWireFormatFactory(options));
IntrospectionSupport.setProperties(server, options);
Map<String, Object> transportOptions = IntrospectionSupport.extractProperties(options, "transport.");
server.setTransportOption(transportOptions);
server.bind();

return server;
} catch (URISyntaxException e) {
throw IOExceptionSupport.create(e);
}
}

/**
* Overriding to allow for proper configuration through reflection.
*/
public Transport compositeConfigure(Transport transport, WireFormat format, Map options) {

SslTransport sslTransport = (SslTransport)transport.narrow(SslTransport.class);
IntrospectionSupport.setProperties(sslTransport, options);

Map<String, Object> socketOptions = IntrospectionSupport.extractProperties(options, "socket.");

sslTransport.setSocketOptions(socketOptions);

if (sslTransport.isTrace()) {
try {
transport = TransportLoggerFactory.getInstance().createTransportLogger(transport,
sslTransport.getLogWriterName(), sslTransport.isDynamicManagement(), sslTransport.isStartLogging(), sslTransport.getJmxPort());
} catch (Throwable e) {
LOG.error("Could not create TransportLogger object for: " + sslTransport.getLogWriterName() + ", reason: " + e, e);
}
}

transport = new InactivityMonitor(transport, format);

// Only need the WireFormatNegotiator if using openwire
if (format instanceof OpenWireFormat) {
transport = new WireFormatNegotiator(transport, (OpenWireFormat)format, sslTransport.getMinmumWireFormatVersion());
}

return transport;
}

/**
* Overriding to use SslTransports.
*/
protected Transport createTransport(URI location, WireFormat wf) throws UnknownHostException, IOException {
URI localLocation = null;
String path = location.getPath();
// see if the path is a local URI location
if (path != null && path.length() > 0) {
int localPortIndex = path.indexOf(':');
try {
Integer.parseInt(path.substring(localPortIndex + 1, path.length()));
String localString = location.getScheme() + ":/" + path;
localLocation = new URI(localString);
} catch (Exception e) {
LOG.warn("path isn't a valid local location for SslTransport to use", e);
}
}
SocketFactory socketFactory = createSocketFactory();
return new SslTransport(wf, (SSLSocketFactory)socketFactory, location, localLocation, false);
}



/**
* Creates a new SSL ServerSocketFactory. The given factory will use
* user-provided key and trust managers (if the user provided them).
*
* @return Newly created (Ssl)ServerSocketFactory.
* @throws IOException
*/
protected ServerSocketFactory createServerSocketFactory() throws IOException {
if( SslContext.getCurrentSslContext()!=null ) {
SslContext ctx = SslContext.getCurrentSslContext();
try {
return ctx.getSSLContext().getServerSocketFactory();
} catch (Exception e) {
throw IOExceptionSupport.create(e);
}
} else {
return SSLServerSocketFactory.getDefault();
}
}

/**
* Creates a new SSL SocketFactory. The given factory will use user-provided
* key and trust managers (if the user provided them).
*
* @return Newly created (Ssl)SocketFactory.
* @throws IOException
*/
protected SocketFactory createSocketFactory() throws IOException {

if( SslContext.getCurrentSslContext()!=null ) {
SslContext ctx = SslContext.getCurrentSslContext();
try {
return ctx.getSSLContext().getSocketFactory();
} catch (Exception e) {
throw IOExceptionSupport.create(e);
}
} else {
return SSLSocketFactory.getDefault();
}

}

/**
*
* @param km
* @param tm
* @param random
* @deprecated "Do not use anymore... using static initializers like this method only allows the JVM to use 1 SSL configuration per broker."
* @see org.apache.activemq.broker.SslContext#setCurrentSslContext(SslContext)
* @see org.apache.activemq.broker.SslContext#getSSLContext()
*/
public void setKeyAndTrustManagers(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
SslContext ctx = new SslContext(km, tm, random);
SslContext.setCurrentSslContext(ctx);
}

}