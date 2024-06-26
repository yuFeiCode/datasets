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
package org.apache.camel.component.jclouds;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.jclouds.blobstore.BlobStore;

public class JcloudsBlobStoreEndpoint extends JcloudsEndpoint {

private String locationId;
private String container;
private String directory;
private String blobName;
private String operation;

private BlobStore blobStore;

public JcloudsBlobStoreEndpoint(String uri, JcloudsComponent component, BlobStore blobStore) {
super(uri, component);
this.blobStore = blobStore;
}

@Override
public Producer createProducer() throws Exception {
return new JcloudsBlobStoreProducer(this, blobStore);
}

@Override
public Consumer createConsumer(Processor processor) {
return new JcloudsBlobStoreConsumer(this, processor, blobStore);
}

public String getLocationId() {
return locationId;
}

public void setLocationId(String locationId) {
this.locationId = locationId;
}

public String getContainer() {
return container;
}

public void setContainer(String container) {
this.container = container;
}

public String getDirectory() {
return directory;
}

public void setDirectory(String directory) {
this.directory = directory;
}

public String getBlobName() {
return blobName;
}

public void setBlobName(String blobName) {
this.blobName = blobName;
}

public String getOperation() {
return operation;
}

public void setOperation(String operation) {
this.operation = operation;
}
}