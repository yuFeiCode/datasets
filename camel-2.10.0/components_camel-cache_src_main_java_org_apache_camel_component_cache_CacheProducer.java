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
package org.apache.camel.component.cache;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Map;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;

import org.apache.camel.Exchange;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.impl.DefaultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheProducer extends DefaultProducer {
private static final transient Logger LOG = LoggerFactory.getLogger(CacheProducer.class);
private CacheConfiguration config;
private Ehcache cache;

public CacheProducer(CacheEndpoint endpoint, CacheConfiguration config) throws Exception {
super(endpoint);
this.config = config;
}

@Override
protected void doStart() throws Exception {
super.doStart();
}

@Override
public CacheEndpoint getEndpoint() {
return (CacheEndpoint) super.getEndpoint();
}

public void process(Exchange exchange) throws Exception {
LOG.trace("Cache Name: {}", config.getCacheName());

cache = getEndpoint().initializeCache();

Map<String, Object> headers = exchange.getIn().getHeaders();
String key = (headers.containsKey(CacheConstants.CACHE_KEY)) ? (String) headers
.get(CacheConstants.CACHE_KEY) : getEndpoint().getKey();

String operation = (headers.containsKey(CacheConstants.CACHE_OPERATION)) ? (String) headers
.get(CacheConstants.CACHE_OPERATION) : getEndpoint().getOperation();

if (operation == null) {
throw new CacheException(CacheConstants.CACHE_OPERATION + " header not specified in message");
}
if ((key == null) && (!checkIsEqual(operation, CacheConstants.CACHE_OPERATION_DELETEALL))) {
throw new CacheException(CacheConstants.CACHE_KEY + " is not specified in message header or endpoint URL.");
}

performCacheOperation(exchange, operation, key);

//cleanup the cache headers
exchange.getIn().removeHeader(CacheConstants.CACHE_KEY);
exchange.getIn().removeHeader(CacheConstants.CACHE_OPERATION);
}

private void performCacheOperation(Exchange exchange, String operation, String key) throws Exception {
Object element;

if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_ADD)) {
LOG.debug("Adding an element with key {} into the Cache", key);
element = createElementFromBody(exchange, CacheConstants.CACHE_OPERATION_ADD);
cache.put(new Element(key, element));
} else if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_UPDATE)) {
LOG.debug("Updating an element with key {} into the Cache", key);
element = createElementFromBody(exchange, CacheConstants.CACHE_OPERATION_UPDATE);
cache.put(new Element(key, element));
} else if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_DELETEALL)) {
LOG.debug("Deleting All elements from the Cache");
cache.removeAll();
} else if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_DELETE)) {
LOG.debug("Deleting an element with key {} into the Cache", key);
cache.remove(key);
} else if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_GET)) {
LOG.debug("Quering an element with key {} from the Cache", key);
if (cache.isKeyInCache(key) && cache.get(key) != null) {
exchange.getIn().setHeader(CacheConstants.CACHE_ELEMENT_WAS_FOUND, true);
exchange.getIn().setBody(cache.get(key).getValue());
} else {
exchange.getIn().removeHeader(CacheConstants.CACHE_ELEMENT_WAS_FOUND);
}
} else if (checkIsEqual(operation, CacheConstants.CACHE_OPERATION_URL_CHECK)) {
LOG.debug("Querying an element with key {} from the Cache", key);
if (cache.isKeyInCache(key)) {
exchange.getIn().setHeader(CacheConstants.CACHE_ELEMENT_WAS_FOUND, true);
} else {
exchange.getIn().removeHeader(CacheConstants.CACHE_ELEMENT_WAS_FOUND);
}
} else {
throw new CacheException(CacheConstants.CACHE_OPERATION + " " + operation + " is not supported.");
}
}

private boolean checkIsEqual(String operation, String constant) {
return operation.equalsIgnoreCase(constant)
|| operation.equalsIgnoreCase(CacheConstants.CACHE_HEADER_PREFIX + constant);
}


private Object createElementFromBody(Exchange exchange, String cacheOperation) throws NoTypeConversionAvailableException {
Object element;
Object body = exchange.getIn().getBody();
if (body == null) {
throw new CacheException("Body cannot be null for operation " + cacheOperation);
} else if (body instanceof Serializable) {
element = body;
} else {
InputStream is = exchange.getContext().getTypeConverter().mandatoryConvertTo(InputStream.class, body);
// Read InputStream into a byte[] buffer
element = exchange.getContext().getTypeConverter().mandatoryConvertTo(byte[].class, is);
}
return element;
}

}