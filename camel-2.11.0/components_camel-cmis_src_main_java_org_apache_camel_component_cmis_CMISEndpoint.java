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
package org.apache.camel.component.cmis;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultEndpoint;

/**
* Represents a CMIS endpoint.
*/
public class CMISEndpoint extends DefaultEndpoint {

private CMISSessionFacade sessionFacade;
private boolean queryMode;

public CMISEndpoint() {
}

public CMISEndpoint(String uri, CMISComponent component) {
super(uri, component);
}

public CMISEndpoint(String uri, CMISComponent cmisComponent, CMISSessionFacade sessionFacade) {
this(uri, cmisComponent);
this.sessionFacade = sessionFacade;
}

public Producer createProducer() throws Exception {
if (this.queryMode) {
return new CMISQueryProducer(this, sessionFacade);
}
return new CMISProducer(this, sessionFacade);
}

public Consumer createConsumer(Processor processor) throws Exception {
return new CMISConsumer(this, processor, sessionFacade);
}

public boolean isSingleton() {
return true;
}

public void setQueryMode(boolean queryMode) {
this.queryMode = queryMode;
}
}