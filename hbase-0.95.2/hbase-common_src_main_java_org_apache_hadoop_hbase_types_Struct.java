/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.hbase.types;

import java.util.Iterator;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.util.Order;
import org.apache.hadoop.hbase.util.PositionedByteRange;

/**
* <p>
* {@code Struct} is a simple {@link DataType} for implementing "compound
* rowkey" and "compound qualifier" schema design strategies.
* </p>
* <h3>Encoding</h3>
* <p>
* {@code Struct} member values are encoded onto the target byte[] in the order
* in which they are declared. A {@code Struct} may be used as a member of
* another {@code Struct}. {@code Struct}s are not {@code nullable} but their
* component fields may be.
* </p>
* <h3>Sort Order</h3>
* <p>
* {@code Struct} instances sort according to the composite order of their
* fields, that is, left-to-right and depth-first. This can also be thought of
* as lexicographic comparison of concatenated members.
* </p>
* <p>
* {@link StructIterator} is provided as a convenience for consuming the
* sequence of values. Users may find it more appropriate to provide their own
* custom {@link DataType} for encoding application objects rather than using
* this {@code Object[]} implementation. Examples are provided in test.
* </p>
* @see StructIterator
* @see DataType#isNullable()
*/
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class Struct implements DataType<Object[]> {

@SuppressWarnings("rawtypes")
protected final DataType[] fields;
protected final boolean isOrderPreserving;
protected final boolean isSkippable;

/**
* Create a new {@code Struct} instance defined as the sequence of
* {@code HDataType}s in {@code memberTypes}.
* <p>
* A {@code Struct} is {@code orderPreserving} when all of its fields
* are {@code orderPreserving}. A {@code Struct} is {@code skippable} when
* all of its fields are {@code skippable}.
* </p>
*/
@SuppressWarnings("rawtypes")
public Struct(DataType[] memberTypes) {
this.fields = memberTypes;
// a Struct is not orderPreserving when any of its fields are not.
boolean preservesOrder = true;
// a Struct is not skippable when any of its fields are not.
boolean skippable = true;
for (int i = 0; i < this.fields.length; i++) {
DataType dt = this.fields[i];
if (!dt.isOrderPreserving()) preservesOrder = false;
if (i < this.fields.length - 2 && !dt.isSkippable()) {
throw new IllegalArgumentException("Field in position " + i
+ " is not skippable. Non-right-most struct fields must be skippable.");
}
if (!dt.isSkippable()) skippable = false;
}
this.isOrderPreserving = preservesOrder;
this.isSkippable = skippable;
}

@Override
public boolean isOrderPreserving() { return isOrderPreserving; }

@Override
public Order getOrder() { return null; }

@Override
public boolean isNullable() { return false; }

@Override
public boolean isSkippable() { return isSkippable; }

@SuppressWarnings("unchecked")
@Override
public int encodedLength(Object[] val) {
assert fields.length == val.length;
int sum = 0;
for (int i = 0; i < fields.length; i++)
sum += fields[i].encodedLength(val[i]);
return sum;
}

@Override
public Class<Object[]> encodedClass() { return Object[].class; }

/**
* Retrieve an {@link Iterator} over the values encoded in {@code src}.
* {@code src}'s position is consumed by consuming this iterator.
*/
public StructIterator iterator(PositionedByteRange src) {
return new StructIterator(src, fields);
}

@Override
public int skip(PositionedByteRange src) {
StructIterator it = iterator(src);
int skipped = 0;
while (it.hasNext())
skipped += it.skip();
return skipped;
}

@Override
public Object[] decode(PositionedByteRange src) {
int i = 0;
Object[] ret = new Object[fields.length];
Iterator<Object> it = iterator(src);
while (it.hasNext())
ret[i++] = it.next();
return ret;
}

/**
* Read the field at {@code index}. {@code src}'s position is not affected.
*/
public Object decode(PositionedByteRange src, int index) {
assert index >= 0;
StructIterator it = iterator(src.shallowCopy());
for (; index > 0; index--)
it.skip();
return it.next();
}

@SuppressWarnings("unchecked")
@Override
public int encode(PositionedByteRange dst, Object[] val) {
assert fields.length == val.length;
int written = 0;
for (int i = 0; i < fields.length; i++) {
written += fields[i].encode(dst, val[i]);
}
return written;
}
}