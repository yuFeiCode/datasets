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
package org.apache.camel.component.netty.handlers;

import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelExchangeException;
import org.apache.camel.Exchange;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.component.netty.NettyCamelState;
import org.apache.camel.component.netty.NettyConstants;
import org.apache.camel.component.netty.NettyHelper;
import org.apache.camel.component.netty.NettyPayloadHelper;
import org.apache.camel.component.netty.NettyProducer;
import org.apache.camel.util.ExchangeHelper;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* Client handler which cannot be shared
*/
public class ClientChannelHandler extends SimpleChannelUpstreamHandler {
private static final transient Logger LOG = LoggerFactory.getLogger(ClientChannelHandler.class);
private final NettyProducer producer;
private volatile boolean messageReceived;
private volatile boolean exceptionHandled;

public ClientChannelHandler(NettyProducer producer) {
this.producer = producer;
}

@Override
public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent channelStateEvent) throws Exception {
// to keep track of open sockets
producer.getAllChannels().add(channelStateEvent.getChannel());
}

@Override
public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent exceptionEvent) throws Exception {
if (LOG.isTraceEnabled()) {
LOG.trace("Exception caught at Channel: " + ctx.getChannel(), exceptionEvent.getCause());

}
if (exceptionHandled) {
// ignore subsequent exceptions being thrown
return;
}

exceptionHandled = true;
Throwable cause = exceptionEvent.getCause();

if (LOG.isDebugEnabled()) {
LOG.debug("Closing channel as an exception was thrown from Netty", cause);
}

Exchange exchange = getExchange(ctx);
AsyncCallback callback = getAsyncCallback(ctx);

// the state may not be set
if (exchange != null && callback != null) {
// set the cause on the exchange
exchange.setException(cause);

// close channel in case an exception was thrown
NettyHelper.close(exceptionEvent.getChannel());

// signal callback
callback.done(false);
}
}

@Override
public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
LOG.trace("Channel closed: {}", ctx.getChannel());

Exchange exchange = getExchange(ctx);
AsyncCallback callback = getAsyncCallback(ctx);

// remove state
producer.removeState(ctx.getChannel());

if (producer.getConfiguration().isSync() && !messageReceived && !exceptionHandled) {
// session was closed but no message received. This could be because the remote server had an internal error
// and could not return a response. We should count down to stop waiting for a response
if (LOG.isDebugEnabled()) {
LOG.debug("Channel closed but no message received from address: {}", producer.getConfiguration().getAddress());
}
exchange.setException(new CamelExchangeException("No response received from remote server: " + producer.getConfiguration().getAddress(), exchange));
// signal callback
callback.done(false);
}
}

@Override
public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {
messageReceived = true;

Exchange exchange = getExchange(ctx);
AsyncCallback callback = getAsyncCallback(ctx);

Object body = messageEvent.getMessage();
LOG.debug("Message received: {}", body);

// if textline enabled then covert to a String which must be used for textline
if (producer.getConfiguration().isTextline()) {
try {
body = producer.getContext().getTypeConverter().mandatoryConvertTo(String.class, exchange, body);
} catch (NoTypeConversionAvailableException e) {
exchange.setException(e);
callback.done(false);
}
}


// set the result on either IN or OUT on the original exchange depending on its pattern
if (ExchangeHelper.isOutCapable(exchange)) {
NettyPayloadHelper.setOut(exchange, body);
} else {
NettyPayloadHelper.setIn(exchange, body);
}

try {
// should channel be closed after complete?
Boolean close;
if (ExchangeHelper.isOutCapable(exchange)) {
close = exchange.getOut().getHeader(NettyConstants.NETTY_CLOSE_CHANNEL_WHEN_COMPLETE, Boolean.class);
} else {
close = exchange.getIn().getHeader(NettyConstants.NETTY_CLOSE_CHANNEL_WHEN_COMPLETE, Boolean.class);
}

// should we disconnect, the header can override the configuration
boolean disconnect = producer.getConfiguration().isDisconnect();
if (close != null) {
disconnect = close;
}
if (disconnect) {
if (LOG.isDebugEnabled()) {
LOG.debug("Closing channel when complete at address: {}", producer.getConfiguration().getAddress());
}
NettyHelper.close(ctx.getChannel());
}
} finally {
// signal callback
callback.done(false);
}
}

private Exchange getExchange(ChannelHandlerContext ctx) {
NettyCamelState state = producer.getState(ctx.getChannel());
return state != null ? state.getExchange() : null;
}

private AsyncCallback getAsyncCallback(ChannelHandlerContext ctx) {
NettyCamelState state = producer.getState(ctx.getChannel());
return state != null ? state.getCallback() : null;
}

}