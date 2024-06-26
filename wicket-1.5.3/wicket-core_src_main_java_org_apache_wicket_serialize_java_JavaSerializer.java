/*
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
package org.apache.wicket.serialize.java;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;

import org.apache.wicket.Application;
import org.apache.wicket.ThreadContext;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.application.IClassResolver;
import org.apache.wicket.serialize.ISerializer;
import org.apache.wicket.settings.IApplicationSettings;
import org.apache.wicket.util.io.IOUtils;
import org.apache.wicket.util.io.SerializableChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* An implementation of {@link ISerializer} based on Java Serialization (ObjectOutputStream,
* ObjectInputStream)
*
* Requires the application key to enable serialization and deserialisation outside thread in which
* application thread local is set
*
* @see IObjectStreamFactory
*/
public class JavaSerializer implements ISerializer
{
private static final Logger log = LoggerFactory.getLogger(JavaSerializer.class);
/**
* The key of the application which can be used later to find the proper {@link IClassResolver}
*/
private final String applicationKey;

/**
* Construct.
*
* @param applicationKey
*/
public JavaSerializer(final String applicationKey)
{
this.applicationKey = applicationKey;
}

public byte[] serialize(final Object object)
{
try
{
final ByteArrayOutputStream out = new ByteArrayOutputStream();
ObjectOutputStream oos = null;
try
{
oos = newObjectOutputStream(out);
oos.writeObject(applicationKey);
oos.writeObject(object);
}
finally
{
try
{
IOUtils.close(oos);
}
finally
{
out.close();
}
}
return out.toByteArray();
}
catch (Exception e)
{
log.error("Error serializing object " + object.getClass() + " [object=" + object + "]",
e);
}
return null;
}

public Object deserialize(final byte[] data)
{
ThreadContext old = ThreadContext.get(false);
final ByteArrayInputStream in = new ByteArrayInputStream(data);
ObjectInputStream ois = null;
try
{
Application oldApplication = ThreadContext.getApplication();
try
{
ois = newObjectInputStream(in);
String applicationName = (String)ois.readObject();
if (applicationName != null)
{
Application app = Application.get(applicationName);
if (app != null)
{
ThreadContext.setApplication(app);
}
}
return ois.readObject();
}
finally
{
try
{
ThreadContext.setApplication(oldApplication);
IOUtils.close(ois);
}
finally
{
in.close();
}
}
}
catch (ClassNotFoundException e)
{
throw new RuntimeException("Could not deserialize object using: " + ois.getClass(), e);
}
catch (IOException e)
{
throw new RuntimeException("Could not deserialize object using: " + ois.getClass(), e);
}
finally
{
ThreadContext.restore(old);
}
}

/**
* Gets a new instance of an {@link ObjectInputStream} with the provided {@link InputStream}.
*
* @param in
*            The input stream that should be used for the reading
* @return a new object input stream instance
* @throws IOException
*             if an I/O error occurs while reading stream header
*/
protected ObjectInputStream newObjectInputStream(InputStream in) throws IOException
{
return new ClassResolverObjectInputStream(in);
}

/**
* Gets a new instance of an {@link ObjectOutputStream} with the provided {@link OutputStream}.
*
* @param out
*            The output stream that should be used for the writing
* @return a new object output stream instance
* @throws IOException
*             if an I/O error occurs while writing stream header
*/
protected ObjectOutputStream newObjectOutputStream(OutputStream out) throws IOException
{
return new CheckerObjectOutputStream(out);
}

/**
* Extend {@link ObjectInputStream} to add framework class resolution logic.
*/
private static class ClassResolverObjectInputStream extends ObjectInputStream
{
public ClassResolverObjectInputStream(InputStream in) throws IOException
{
super(in);
}

// This override is required to resolve classes inside in different bundle, i.e.
// The classes can be resolved by OSGI classresolver implementation
@Override
protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException,
ClassNotFoundException
{
String className = desc.getName();

try
{
return super.resolveClass(desc);
}
catch (ClassNotFoundException ex1)
{
// ignore this exception.
log.debug("Class not found by the object outputstream itself, trying the IClassResolver");
}


Class<?> candidate = null;
try
{
// Can the application always be taken??
// Should be if serialization happened in thread with application set
// (WICKET-2195)
Application application = Application.get();
IApplicationSettings applicationSettings = application.getApplicationSettings();
IClassResolver classResolver = applicationSettings.getClassResolver();

candidate = classResolver.resolveClass(className);
if (candidate == null)
{
candidate = super.resolveClass(desc);
}
}
catch (WicketRuntimeException ex)
{
if (ex.getCause() instanceof ClassNotFoundException)
{
throw (ClassNotFoundException)ex.getCause();
}
}
return candidate;
}
}
/**
* Write objects to the wrapped output stream and log a meaningful message for serialization
* problems
*/
private static class CheckerObjectOutputStream extends ObjectOutputStream
{
public CheckerObjectOutputStream(OutputStream out) throws IOException
{
super(out);
}

@Override
protected final void writeObjectOverride(final Object obj) throws IOException
{
try
{
super.writeObject(obj);
}
catch (NotSerializableException nsx)
{
if (SerializableChecker.isAvailable())
{
// trigger serialization again, but this time gather
// some more info
new SerializableChecker(nsx).writeObject(obj);
// if we get here, we didn't fail, while we
// should;
throw nsx;
}
throw nsx;
}
catch (Exception e)
{
log.error("error writing object " + obj + ": " + e.getMessage(), e);
throw new WicketRuntimeException(e);
}
}

@Override
public void flush() throws IOException
{
super.flush();
}

@Override
public void close() throws IOException
{
super.close();
}
}
}