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
package org.apache.camel.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.camel.NoSuchBeanException;
import org.apache.camel.spi.Registry;
import org.osgi.framework.Bundle;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.container.NoSuchComponentException;
import org.osgi.service.blueprint.reflect.BeanMetadata;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.ReferenceMetadata;

public class BlueprintContainerRegistry implements Registry {

private final BlueprintContainer blueprintContainer;

public BlueprintContainerRegistry(BlueprintContainer blueprintContainer) {
this.blueprintContainer = blueprintContainer;
}

public Object lookup(String name) {
return blueprintContainer.getComponentInstance(name);
}

public <T> T lookup(String name, Class<T> type) {
Object answer;
try {
answer = blueprintContainer.getComponentInstance(name);
} catch (NoSuchComponentException e) {
return null;
}

// just to be safe
if (answer == null) {
return null;
}

try {
return type.cast(answer);
} catch (Throwable e) {
String msg = "Found bean: " + name + " in BlueprintContainer: " + blueprintContainer
+ " of type: " + answer.getClass().getName() + " expected type was: " + type;
throw new NoSuchBeanException(name, msg, e);
}
}

public <T> Map<String, T> lookupByType(Class<T> type) {
return lookupByType(blueprintContainer, type);
}

@SuppressWarnings("unchecked")
public static <T> Map<String, T> lookupByType(BlueprintContainer blueprintContainer, Class<T> type) {
Bundle bundle = (Bundle) blueprintContainer.getComponentInstance("blueprintBundle");
Map<String, T> objects = new LinkedHashMap<String, T>();
Set<String> ids = blueprintContainer.getComponentIds();
for (String id : ids) {
try {
ComponentMetadata metadata = blueprintContainer.getComponentMetadata(id);
Class<?> cl = null;
if (metadata instanceof BeanMetadata) {
BeanMetadata beanMetadata = (BeanMetadata)metadata;
cl = bundle.loadClass(beanMetadata.getClassName());
} else if (metadata instanceof ReferenceMetadata) {
ReferenceMetadata referenceMetadata = (ReferenceMetadata)metadata;
cl = bundle.loadClass(referenceMetadata.getInterface());
}
if (cl != null && type.isAssignableFrom(cl)) {
Object o = blueprintContainer.getComponentInstance(metadata.getId());
objects.put(metadata.getId(), type.cast(o));
}
} catch (Throwable t) {
// ignore
}
}
return objects;
}
}