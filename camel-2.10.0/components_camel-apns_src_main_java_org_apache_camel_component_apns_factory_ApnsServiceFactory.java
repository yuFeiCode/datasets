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
package org.apache.camel.component.apns.factory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import javax.net.ssl.SSLContext;

import com.notnoop.apns.APNS;
import com.notnoop.apns.ApnsDelegate;
import com.notnoop.apns.ApnsService;
import com.notnoop.apns.ApnsServiceBuilder;
import com.notnoop.apns.ReconnectPolicy;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.component.apns.model.ConnectionStrategy;
import org.apache.camel.component.apns.model.ReconnectionPolicy;
import org.apache.camel.component.apns.util.AssertUtils;
import org.apache.camel.component.apns.util.ParamUtils;
import org.apache.camel.component.apns.util.ResourceUtils;
import org.apache.camel.util.ObjectHelper;

public class ApnsServiceFactory implements CamelContextAware {

private static final int DEFAULT_POOL_SIZE = 10;
private static final int MIN_POOL_SIZE = 1;
private static final int MAX_POOL_SIZE = 30;

private CamelContext camelContext;
private String certificatePath;
private String certificatePassword;
private ConnectionStrategy connectionStrategy;
private ReconnectionPolicy reconnectionPolicy;
private SSLContext sslContext;
private int poolSize = DEFAULT_POOL_SIZE;
private String gatewayHost;
private int gatewayPort;
private String feedbackHost;
private int feedbackPort;
private ApnsDelegate apnsDelegate;

public ApnsServiceFactory() {
}

public ApnsServiceFactory(CamelContext camelContext) {
this.camelContext = camelContext;
}

public CamelContext getCamelContext() {
return camelContext;
}

public void setCamelContext(CamelContext camelContext) {
this.camelContext = camelContext;
}

public String getFeedbackHost() {
return feedbackHost;
}

public void setFeedbackHost(String feedbackHost) {
this.feedbackHost = feedbackHost;
}

public String getGatewayHost() {
return gatewayHost;
}

public void setGatewayHost(String gatewayHost) {
this.gatewayHost = gatewayHost;
}

public int getGatewayPort() {
return gatewayPort;
}

public void setGatewayPort(int gatewayPort) {
this.gatewayPort = gatewayPort;
}

public int getFeedbackPort() {
return feedbackPort;
}

public void setFeedbackPort(int feedbackPort) {
this.feedbackPort = feedbackPort;
}

public int getPoolSize() {
return poolSize;
}

public void setPoolSize(int poolSize) {
this.poolSize = poolSize;
}

public String getCertificatePath() {
return certificatePath;
}

public void setCertificatePath(String certificatePath) {
this.certificatePath = certificatePath;
}

public String getCertificatePassword() {
return certificatePassword;
}

public void setCertificatePassword(String certificatePassword) {
this.certificatePassword = certificatePassword;
}

public ReconnectionPolicy getReconnectionPolicy() {
return reconnectionPolicy;
}

public void setReconnectionPolicy(ReconnectionPolicy reconnectionPolicy) {
this.reconnectionPolicy = reconnectionPolicy;
}

public ConnectionStrategy getConnectionStrategy() {
return connectionStrategy;
}

public void setConnectionStrategy(ConnectionStrategy connectionStrategy) {
this.connectionStrategy = connectionStrategy;
}

public SSLContext getSslContext() {
return sslContext;
}

public void setSslContext(SSLContext sslContext) {
this.sslContext = sslContext;
}

public ApnsDelegate getApnsDelegate() {
return apnsDelegate;
}

public ApnsService getApnsService() {
ApnsServiceBuilder builder = APNS.newService();

configureConnectionStrategy(builder);
configureReconnectionPolicy(builder);
configureApnsDelegate(builder);
configureApnsDestinations(builder);
try {
configureApnsCertificate(builder);
} catch (FileNotFoundException e) {
throw ObjectHelper.wrapRuntimeCamelException(e);
}

ApnsService apnsService = builder.build();
return apnsService;
}

private void configureApnsCertificate(ApnsServiceBuilder builder) throws FileNotFoundException {
if (getSslContext() != null) {
builder.withSSLContext(getSslContext());
return;
}

ObjectHelper.notNull(getCamelContext(), "camelContext");
ObjectHelper.notEmpty(getCertificatePath(), "certificatePath");
ObjectHelper.notEmpty(getCertificatePassword(), "certificatePassword");

InputStream certificateInputStream = null;
try {
certificateInputStream = camelContext.getClassResolver().loadResourceAsStream(getCertificatePath());
if (certificateInputStream == null) {
throw new FileNotFoundException("Cannot load " + getCertificatePath() + " from classpath");
}
builder.withCert(certificateInputStream, getCertificatePassword()).withProductionDestination();
} finally {
ResourceUtils.close(certificateInputStream);
}
}

private void configureApnsDestinations(ApnsServiceBuilder builder) {
ParamUtils.checkDestination(getGatewayHost(), getGatewayPort(), "gateway");
ParamUtils.checkDestination(getFeedbackHost(), getFeedbackPort(), "feedback");

if (ObjectHelper.isNotEmpty(getGatewayHost())) {
builder.withGatewayDestination(getGatewayHost(), getGatewayPort());
}
if (ObjectHelper.isNotEmpty(getFeedbackHost())) {
builder.withFeedbackDestination(getFeedbackHost(), getFeedbackPort());
}
}

private void configureApnsDelegate(ApnsServiceBuilder builder) {
if (apnsDelegate != null) {
builder.withDelegate(apnsDelegate);
}
}

private void configureConnectionStrategy(ApnsServiceBuilder builder) {
if (getConnectionStrategy() == ConnectionStrategy.POOL) {
AssertUtils.isTrue(poolSize >= MIN_POOL_SIZE, "Pool size needs to be greater than: " + MIN_POOL_SIZE);
AssertUtils.isTrue(poolSize <= MAX_POOL_SIZE, "Pool size needs to be lower than: " + MAX_POOL_SIZE);
}

if (getConnectionStrategy() == null) {
return;
}

switch (getConnectionStrategy()) {
case NON_BLOCKING:
builder.asNonBlocking();
break;
case QUEUE:
builder.asQueued();
break;
case POOL:
builder.asPool(getPoolSize());
break;
default:
break;
}
}

private void configureReconnectionPolicy(ApnsServiceBuilder builder) {
if (getReconnectionPolicy() == null) {
return;
}

switch (getReconnectionPolicy()) {
case EVERY_HALF_HOUR:
builder.withReconnectPolicy(ReconnectPolicy.Provided.EVERY_HALF_HOUR);
break;
case EVERY_NOTIFICATION:
builder.withReconnectPolicy(ReconnectPolicy.Provided.EVERY_NOTIFICATION);
break;
default:
builder.withReconnectPolicy(ReconnectPolicy.Provided.NEVER);
break;
}
}

}