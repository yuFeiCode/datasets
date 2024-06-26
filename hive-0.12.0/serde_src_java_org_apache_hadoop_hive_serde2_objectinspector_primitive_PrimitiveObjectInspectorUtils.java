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

package org.apache.hadoop.hive.serde2.objectinspector.primitive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.common.type.HiveVarchar;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.io.HiveVarcharWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.hive.serde2.io.TimestampWritable;
import org.apache.hadoop.hive.serde2.lazy.LazyInteger;
import org.apache.hadoop.hive.serde2.lazy.LazyLong;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.typeinfo.BaseTypeParams;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeSpec;
import org.apache.hadoop.hive.serde2.typeinfo.VarcharTypeParams;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;

/**
* ObjectInspectorFactory is the primary way to create new ObjectInspector
* instances.
*
* SerDe classes should call the static functions in this library to create an
* ObjectInspector to return to the caller of SerDe2.getObjectInspector().
*/
public final class PrimitiveObjectInspectorUtils {
private static Log LOG = LogFactory.getLog(PrimitiveObjectInspectorUtils.class);

/**
* TypeEntry stores information about a Hive Primitive TypeInfo.
*/
public static class PrimitiveTypeEntry implements Writable, Cloneable, PrimitiveTypeSpec {

/**
* The category of the PrimitiveType.
*/
public PrimitiveObjectInspector.PrimitiveCategory primitiveCategory;

/**
* primitiveJavaType refers to java types like int, double, etc.
*/
public Class<?> primitiveJavaType;
/**
* primitiveJavaClass refers to java classes like Integer, Double, String
* etc.
*/
public Class<?> primitiveJavaClass;
/**
* writableClass refers to hadoop Writable classes like IntWritable,
* DoubleWritable, Text etc.
*/
public Class<?> primitiveWritableClass;
/**
* typeName is the name of the type as in DDL.
*/
public String typeName;
public Class<?> typeParamsClass;
public BaseTypeParams typeParams;

PrimitiveTypeEntry(
PrimitiveObjectInspector.PrimitiveCategory primitiveCategory,
String typeName, Class<?> primitiveType, Class<?> javaClass,
Class<?> hiveClass, Class<?>paramsClass) {
this.primitiveCategory = primitiveCategory;
primitiveJavaType = primitiveType;
primitiveJavaClass = javaClass;
primitiveWritableClass = hiveClass;
typeParamsClass = paramsClass;
this.typeName = typeName;
}

@Override
public void readFields(DataInput in) throws IOException {
primitiveCategory = WritableUtils.readEnum(in,
PrimitiveObjectInspector.PrimitiveCategory.class);
typeName = WritableUtils.readString(in);
int typeParamsIndicator = WritableUtils.readVInt(in);
try {
primitiveJavaType = Class.forName(WritableUtils.readString(in));
primitiveJavaClass = Class.forName(WritableUtils.readString(in));
primitiveWritableClass = Class.forName(WritableUtils.readString(in));
if (typeParamsIndicator == 1) {
typeParamsClass = Class.forName(WritableUtils.readString(in));
typeParams = (BaseTypeParams)typeParamsClass.newInstance();
typeParams.readFields(in);
} else {
typeParamsClass = null;
typeParams = null;
}
} catch (ClassNotFoundException e) {
throw new IOException(e);
} catch (IllegalAccessException e) {
throw new IOException(e);
} catch (InstantiationException e) {
throw new IOException(e);
}
}

@Override
public void write(DataOutput out) throws IOException {
int typeParamsIndicator = (isParameterized() && typeParams != null) ? 1 : 0;

WritableUtils.writeEnum(out, primitiveCategory);
WritableUtils.writeString(out, typeName);
WritableUtils.writeVInt(out, typeParamsIndicator);
WritableUtils.writeString(out, primitiveJavaType.getName());
WritableUtils.writeString(out, primitiveJavaClass.getName());
WritableUtils.writeString(out, primitiveWritableClass.getName());
if (typeParamsIndicator == 1) {
WritableUtils.writeString(out,  typeParamsClass.getName());
typeParams.write(out);
}
}

public PrimitiveTypeEntry addParameters(String[] parameters) {
if (parameters == null || parameters.length == 0) {
return this;
}

PrimitiveTypeEntry result;
try {
BaseTypeParams newTypeParams = (BaseTypeParams)typeParamsClass.newInstance();
newTypeParams.set(parameters);
String typeNameWithParams = this.typeName + newTypeParams.toString();
if (typeNameToTypeEntry.containsKey(typeNameWithParams)) {
return typeNameToTypeEntry.get(typeNameWithParams);
}
result = (PrimitiveTypeEntry)this.clone();
result.typeParams = newTypeParams;

PrimitiveObjectInspectorUtils.addParameterizedType(result);

return result;
} catch (Exception err) {
LOG.error("Error while setting type parameters: " + err);
throw new RuntimeException(err);
}
}

public boolean isParameterized() {
return (null != typeParamsClass);
}

@Override
public Object clone() {
PrimitiveTypeEntry result = new PrimitiveTypeEntry(
this.primitiveCategory,
this.typeName,
this.primitiveJavaType,
this.primitiveJavaClass,
this.primitiveWritableClass,
this.typeParamsClass);
return result;
}

@Override
public String toString() {
if (typeParams != null) {
return typeName + typeParams.toString();
}
return typeName;
}

public static BaseTypeParams createTypeParams(String typeName, String[] parameters)
throws SerDeException {
try {
PrimitiveTypeEntry typeEntry = getTypeEntryFromTypeName(typeName);
if (typeEntry != null && typeEntry.typeParamsClass != null) {
BaseTypeParams newTypeParams = (BaseTypeParams)typeEntry.typeParamsClass.newInstance();
newTypeParams.set(parameters);
return newTypeParams;
} else {
return null;
}
} catch (Exception err) {
throw new SerDeException("Error creating type params for " + typeName
+ ": " + err, err);
}
}

@Override
public PrimitiveCategory getPrimitiveCategory() {
return primitiveCategory;
}

@Override
public BaseTypeParams getTypeParams() {
return typeParams;
}
}

static final Map<PrimitiveCategory, PrimitiveTypeEntry> primitiveCategoryToTypeEntry = new HashMap<PrimitiveCategory, PrimitiveTypeEntry>();
static final Map<Class<?>, PrimitiveTypeEntry> primitiveJavaTypeToTypeEntry = new HashMap<Class<?>, PrimitiveTypeEntry>();
static final Map<Class<?>, PrimitiveTypeEntry> primitiveJavaClassToTypeEntry = new HashMap<Class<?>, PrimitiveTypeEntry>();
static final Map<Class<?>, PrimitiveTypeEntry> primitiveWritableClassToTypeEntry = new HashMap<Class<?>, PrimitiveTypeEntry>();
static final Map<String, PrimitiveTypeEntry> typeNameToTypeEntry = new HashMap<String, PrimitiveTypeEntry>();

static void addParameterizedType(PrimitiveTypeEntry t) {
typeNameToTypeEntry.put(t.toString(), t);
}

static void registerType(PrimitiveTypeEntry t) {
if (t.primitiveCategory != PrimitiveCategory.UNKNOWN) {
primitiveCategoryToTypeEntry.put(t.primitiveCategory, t);
}
if (t.primitiveJavaType != null) {
primitiveJavaTypeToTypeEntry.put(t.primitiveJavaType, t);
}
if (t.primitiveJavaClass != null) {
primitiveJavaClassToTypeEntry.put(t.primitiveJavaClass, t);
}
if (t.primitiveWritableClass != null) {
primitiveWritableClassToTypeEntry.put(t.primitiveWritableClass, t);
}
if (t.typeName != null) {
typeNameToTypeEntry.put(t.typeName, t);
}
}

public static final PrimitiveTypeEntry binaryTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.BINARY, serdeConstants.BINARY_TYPE_NAME, byte[].class,
byte[].class, BytesWritable.class, null);
public static final PrimitiveTypeEntry stringTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.STRING, serdeConstants.STRING_TYPE_NAME, null, String.class,
Text.class, null);
public static final PrimitiveTypeEntry booleanTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.BOOLEAN, serdeConstants.BOOLEAN_TYPE_NAME, Boolean.TYPE,
Boolean.class, BooleanWritable.class, null);
public static final PrimitiveTypeEntry intTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.INT, serdeConstants.INT_TYPE_NAME, Integer.TYPE,
Integer.class, IntWritable.class, null);
public static final PrimitiveTypeEntry longTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.LONG, serdeConstants.BIGINT_TYPE_NAME, Long.TYPE,
Long.class, LongWritable.class, null);
public static final PrimitiveTypeEntry floatTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.FLOAT, serdeConstants.FLOAT_TYPE_NAME, Float.TYPE,
Float.class, FloatWritable.class, null);
public static final PrimitiveTypeEntry voidTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.VOID, serdeConstants.VOID_TYPE_NAME, Void.TYPE, Void.class,
NullWritable.class, null);

// No corresponding Writable classes for the following 3 in hadoop 0.17.0
public static final PrimitiveTypeEntry doubleTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.DOUBLE, serdeConstants.DOUBLE_TYPE_NAME, Double.TYPE,
Double.class, DoubleWritable.class, null);
public static final PrimitiveTypeEntry byteTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.BYTE, serdeConstants.TINYINT_TYPE_NAME, Byte.TYPE,
Byte.class, ByteWritable.class, null);
public static final PrimitiveTypeEntry shortTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.SHORT, serdeConstants.SMALLINT_TYPE_NAME, Short.TYPE,
Short.class, ShortWritable.class, null);
public static final PrimitiveTypeEntry dateTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.DATE, serdeConstants.DATE_TYPE_NAME, null,
Date.class, DateWritable.class, null);
public static final PrimitiveTypeEntry timestampTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.TIMESTAMP, serdeConstants.TIMESTAMP_TYPE_NAME, null,
Timestamp.class, TimestampWritable.class, null);
public static final PrimitiveTypeEntry decimalTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.DECIMAL, serdeConstants.DECIMAL_TYPE_NAME, null,
HiveDecimal.class, HiveDecimalWritable.class, null);
public static final PrimitiveTypeEntry varcharTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.VARCHAR, serdeConstants.VARCHAR_TYPE_NAME, null, HiveVarchar.class,
HiveVarcharWritable.class, VarcharTypeParams.class);

// The following is a complex type for special handling
public static final PrimitiveTypeEntry unknownTypeEntry = new PrimitiveTypeEntry(
PrimitiveCategory.UNKNOWN, "unknown", null, Object.class, null, null);

static {
registerType(binaryTypeEntry);
registerType(stringTypeEntry);
registerType(varcharTypeEntry);
registerType(booleanTypeEntry);
registerType(intTypeEntry);
registerType(longTypeEntry);
registerType(floatTypeEntry);
registerType(voidTypeEntry);
registerType(doubleTypeEntry);
registerType(byteTypeEntry);
registerType(shortTypeEntry);
registerType(dateTypeEntry);
registerType(timestampTypeEntry);
registerType(decimalTypeEntry);
registerType(unknownTypeEntry);
}

/**
* Return Whether the class is a Java Primitive type or a Java Primitive
* class.
*/
public static Class<?> primitiveJavaTypeToClass(Class<?> clazz) {
PrimitiveTypeEntry t = primitiveJavaTypeToTypeEntry.get(clazz);
return t == null ? clazz : t.primitiveJavaClass;
}

/**
* Whether the class is a Java Primitive type or a Java Primitive class.
*/
public static boolean isPrimitiveJava(Class<?> clazz) {
return primitiveJavaTypeToTypeEntry.get(clazz) != null
|| primitiveJavaClassToTypeEntry.get(clazz) != null;
}

/**
* Whether the class is a Java Primitive type.
*/
public static boolean isPrimitiveJavaType(Class<?> clazz) {
return primitiveJavaTypeToTypeEntry.get(clazz) != null;
}

/**
* Whether the class is a Java Primitive class.
*/
public static boolean isPrimitiveJavaClass(Class<?> clazz) {
return primitiveJavaClassToTypeEntry.get(clazz) != null;
}

/**
* Whether the class is a Hive Primitive Writable class.
*/
public static boolean isPrimitiveWritableClass(Class<?> clazz) {
return primitiveWritableClassToTypeEntry.get(clazz) != null;
}

/**
* Get the typeName from a Java Primitive Type or Java PrimitiveClass.
*/
public static String getTypeNameFromPrimitiveJava(Class<?> clazz) {
PrimitiveTypeEntry t = primitiveJavaTypeToTypeEntry.get(clazz);
if (t == null) {
t = primitiveJavaClassToTypeEntry.get(clazz);
}
return t == null ? null : t.typeName;
}

/**
* Get the typeName from a Primitive Writable Class.
*/
public static String getTypeNameFromPrimitiveWritable(Class<?> clazz) {
PrimitiveTypeEntry t = primitiveWritableClassToTypeEntry.get(clazz);
return t == null ? null : t.typeName;
}

/**
* Get the typeName from a Java Primitive Type or Java PrimitiveClass.
*/
public static PrimitiveTypeEntry getTypeEntryFromPrimitiveCategory(
PrimitiveCategory category) {
return primitiveCategoryToTypeEntry.get(category);
}

/**
* Get the TypeEntry for a Java Primitive Type or Java PrimitiveClass.
*/
public static PrimitiveTypeEntry getTypeEntryFromPrimitiveJava(Class<?> clazz) {
PrimitiveTypeEntry t = primitiveJavaTypeToTypeEntry.get(clazz);
if (t == null) {
t = primitiveJavaClassToTypeEntry.get(clazz);
}
return t;
}

/**
* Get the TypeEntry for a Java Primitive Type or Java PrimitiveClass.
*/
public static PrimitiveTypeEntry getTypeEntryFromPrimitiveJavaType(
Class<?> clazz) {
return primitiveJavaTypeToTypeEntry.get(clazz);
}

/**
* Get the TypeEntry for a Java Primitive Type or Java PrimitiveClass.
*/
public static PrimitiveTypeEntry getTypeEntryFromPrimitiveJavaClass(
Class<?> clazz) {
return primitiveJavaClassToTypeEntry.get(clazz);
}

/**
* Get the TypeEntry for a Primitive Writable Class.
*/
public static PrimitiveTypeEntry getTypeEntryFromPrimitiveWritableClass(
Class<?> clazz) {
return primitiveWritableClassToTypeEntry.get(clazz);
}

/**
* Get the TypeEntry for a Primitive Writable Class.
*/
public static PrimitiveTypeEntry getTypeEntryFromTypeName(String typeName) {
return typeNameToTypeEntry.get(typeName);
}

public static PrimitiveTypeEntry getTypeEntryFromTypeSpecs(
PrimitiveCategory primitiveCategory,
BaseTypeParams typeParams) {
if (typeParams == null) {
// No type params, can just use the primitive category
return getTypeEntryFromPrimitiveCategory(primitiveCategory);
}

// Type params were passed in. First check for cached version
String typeString = primitiveCategory.toString().toLowerCase();
typeString += typeParams.toString();
PrimitiveTypeEntry typeEntry = getTypeEntryFromTypeName(typeString);
if (typeEntry == null) {
// Parameterized type doesn't exist yet, create now.
typeEntry =
(PrimitiveTypeEntry) getTypeEntryFromPrimitiveCategory(primitiveCategory).clone();
if (!typeEntry.isParameterized()) {
throw new IllegalArgumentException(
primitiveCategory + " type was being used with type parameters "
+ typeParams + ", which should not be allowed");
}
typeEntry.typeParams = typeParams;
addParameterizedType(typeEntry);
}
return typeEntry;
}

/**
* Compare 2 primitive objects. Conversion not allowed. Note that NULL does
* not equal to NULL according to SQL standard.
*/
public static boolean comparePrimitiveObjects(Object o1,
PrimitiveObjectInspector oi1, Object o2, PrimitiveObjectInspector oi2) {
if (o1 == null || o2 == null) {
return false;
}

if (oi1.getPrimitiveCategory() != oi2.getPrimitiveCategory()) {
return false;
}
switch (oi1.getPrimitiveCategory()) {
case BOOLEAN: {
return ((BooleanObjectInspector) oi1).get(o1) == ((BooleanObjectInspector) oi2)
.get(o2);
}
case BYTE: {
return ((ByteObjectInspector) oi1).get(o1) == ((ByteObjectInspector) oi2)
.get(o2);
}
case SHORT: {
return ((ShortObjectInspector) oi1).get(o1) == ((ShortObjectInspector) oi2)
.get(o2);
}
case INT: {
return ((IntObjectInspector) oi1).get(o1) == ((IntObjectInspector) oi2)
.get(o2);
}
case LONG: {
return ((LongObjectInspector) oi1).get(o1) == ((LongObjectInspector) oi2)
.get(o2);
}
case FLOAT: {
return ((FloatObjectInspector) oi1).get(o1) == ((FloatObjectInspector) oi2)
.get(o2);
}
case DOUBLE: {
return ((DoubleObjectInspector) oi1).get(o1) == ((DoubleObjectInspector) oi2)
.get(o2);
}
case STRING: {
Writable t1 = ((StringObjectInspector) oi1)
.getPrimitiveWritableObject(o1);
Writable t2 = ((StringObjectInspector) oi2)
.getPrimitiveWritableObject(o2);
return t1.equals(t2);
}
case VARCHAR: {
return ((HiveVarcharObjectInspector)oi1).getPrimitiveWritableObject(o1)
.equals(((HiveVarcharObjectInspector)oi2).getPrimitiveWritableObject(o2));
}
case DATE: {
return ((DateObjectInspector) oi1).getPrimitiveWritableObject(o1)
.equals(((DateObjectInspector) oi2).getPrimitiveWritableObject(o2));
}
case TIMESTAMP: {
return ((TimestampObjectInspector) oi1).getPrimitiveWritableObject(o1)
.equals(((TimestampObjectInspector) oi2).getPrimitiveWritableObject(o2));
}
case BINARY: {
return ((BinaryObjectInspector) oi1).getPrimitiveWritableObject(o1).
equals(((BinaryObjectInspector) oi2).getPrimitiveWritableObject(o2));
}
case DECIMAL: {
return ((HiveDecimalObjectInspector) oi1).getPrimitiveJavaObject(o1)
.compareTo(((HiveDecimalObjectInspector) oi2).getPrimitiveJavaObject(o2)) == 0;
}
default:
return false;
}
}

/**
* Convert a primitive object to double.
*/
public static double convertPrimitiveToDouble(Object o, PrimitiveObjectInspector oi) {
switch (oi.getPrimitiveCategory()) {
case BOOLEAN:
return ((BooleanObjectInspector) oi).get(o) ? 1 : 0;
case BYTE:
return ((ByteObjectInspector) oi).get(o);
case SHORT:
return ((ShortObjectInspector) oi).get(o);
case INT:
return ((IntObjectInspector) oi).get(o);
case LONG:
return ((LongObjectInspector) oi).get(o);
case FLOAT:
return ((FloatObjectInspector) oi).get(o);
case DOUBLE:
return ((DoubleObjectInspector) oi).get(o);
case STRING:
return Double.valueOf(((StringObjectInspector) oi).getPrimitiveJavaObject(o));
case TIMESTAMP:
return ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o)
.getDouble();
case DECIMAL:
return ((HiveDecimalObjectInspector) oi).getPrimitiveJavaObject(o).doubleValue();
case DATE:  // unsupported conversion
default:
throw new NumberFormatException();
}
}

/**
* Compare 2 Primitive Objects with their Object Inspector, conversions
* allowed. Note that NULL does not equal to NULL according to SQL standard.
*/
public static boolean comparePrimitiveObjectsWithConversion(Object o1,
PrimitiveObjectInspector oi1, Object o2, PrimitiveObjectInspector oi2) {
if (o1 == null || o2 == null) {
return false;
}

if (oi1.getPrimitiveCategory() == oi2.getPrimitiveCategory()) {
return comparePrimitiveObjects(o1, oi1, o2, oi2);
}

// If not equal, convert all to double and compare
try {
return convertPrimitiveToDouble(o1, oi1) == convertPrimitiveToDouble(o2,
oi2);
} catch (NumberFormatException e) {
return false;
}
}

/**
* Get the boolean value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static boolean getBoolean(Object o, PrimitiveObjectInspector oi) {
boolean result = false;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = false;
break;
case BOOLEAN:
result = ((BooleanObjectInspector) oi).get(o);
break;
case BYTE:
result = ((ByteObjectInspector) oi).get(o) != 0;
break;
case SHORT:
result = ((ShortObjectInspector) oi).get(o) != 0;
break;
case INT:
result = ((IntObjectInspector) oi).get(o) != 0;
break;
case LONG:
result = (int) ((LongObjectInspector) oi).get(o) != 0;
break;
case FLOAT:
result = (int) ((FloatObjectInspector) oi).get(o) != 0;
break;
case DOUBLE:
result = (int) ((DoubleObjectInspector) oi).get(o) != 0;
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
if (soi.preferWritable()) {
Text t = soi.getPrimitiveWritableObject(o);
result = t.getLength() != 0;
} else {
String s = soi.getPrimitiveJavaObject(o);
result = s.length() != 0;
}
break;
case TIMESTAMP:
result = (((TimestampObjectInspector) oi)
.getPrimitiveWritableObject(o).getSeconds() != 0);
break;
case DECIMAL:
result = HiveDecimal.ZERO.compareTo(
((HiveDecimalObjectInspector) oi).getPrimitiveJavaObject(o)) != 0;
break;
case DATE:  // unsupported conversion
default:
throw new RuntimeException("Hive 2 Internal error: unsupported conversion from type: "
+ oi.getTypeName());
}
return result;
}

/**
* Get the byte value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static byte getByte(Object o, PrimitiveObjectInspector oi) {
return (byte) getInt(o, oi);
}

/**
* Get the short value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static short getShort(Object o, PrimitiveObjectInspector oi) {
return (short) getInt(o, oi);
}

/**
* Get the integer value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static int getInt(Object o, PrimitiveObjectInspector oi) {
int result = 0;
switch (oi.getPrimitiveCategory()) {
case VOID: {
result = 0;
break;
}
case BOOLEAN: {
result = (((BooleanObjectInspector) oi).get(o) ? 1 : 0);
break;
}
case BYTE: {
result = ((ByteObjectInspector) oi).get(o);
break;
}
case SHORT: {
result = ((ShortObjectInspector) oi).get(o);
break;
}
case INT: {
result = ((IntObjectInspector) oi).get(o);
break;
}
case LONG: {
result = (int) ((LongObjectInspector) oi).get(o);
break;
}
case FLOAT: {
result = (int) ((FloatObjectInspector) oi).get(o);
break;
}
case DOUBLE: {
result = (int) ((DoubleObjectInspector) oi).get(o);
break;
}
case STRING: {
StringObjectInspector soi = (StringObjectInspector) oi;
if (soi.preferWritable()) {
Text t = soi.getPrimitiveWritableObject(o);
result = LazyInteger.parseInt(t.getBytes(), 0, t.getLength());
} else {
String s = soi.getPrimitiveJavaObject(o);
result = Integer.parseInt(s);
}
break;
}
case VARCHAR: {
result = Integer.parseInt(getString(o, oi));
break;
}
case TIMESTAMP:
result = (int) (((TimestampObjectInspector) oi)
.getPrimitiveWritableObject(o).getSeconds());
break;
case DECIMAL:
result = ((HiveDecimalObjectInspector) oi)
.getPrimitiveJavaObject(o).intValue();
break;
case DATE:  // unsupported conversion
default: {
throw new RuntimeException("Hive 2 Internal error: unsupported conversion from type: "
+ oi.getTypeName());
}
}
return result;
}

/**
* Get the long value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static long getLong(Object o, PrimitiveObjectInspector oi) {
long result = 0;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = 0;
break;
case BOOLEAN:
result = (((BooleanObjectInspector) oi).get(o) ? 1 : 0);
break;
case BYTE:
result = ((ByteObjectInspector) oi).get(o);
break;
case SHORT:
result = ((ShortObjectInspector) oi).get(o);
break;
case INT:
result = ((IntObjectInspector) oi).get(o);
break;
case LONG:
result = ((LongObjectInspector) oi).get(o);
break;
case FLOAT:
result = (long) ((FloatObjectInspector) oi).get(o);
break;
case DOUBLE:
result = (long) ((DoubleObjectInspector) oi).get(o);
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
if (soi.preferWritable()) {
Text t = soi.getPrimitiveWritableObject(o);
result = LazyLong.parseLong(t.getBytes(), 0, t.getLength());
} else {
String s = soi.getPrimitiveJavaObject(o);
result = Long.parseLong(s);
}
break;
case VARCHAR: {
result = Long.parseLong(getString(o, oi));
break;
}
case TIMESTAMP:
result = ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o)
.getSeconds();
break;
case DECIMAL:
result = ((HiveDecimalObjectInspector) oi)
.getPrimitiveJavaObject(o).longValue();
break;
case DATE:  // unsupported conversion
default:
throw new RuntimeException("Hive 2 Internal error: unsupported conversion from type: "
+ oi.getTypeName());
}
return result;
}

/**
* Get the double value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static double getDouble(Object o, PrimitiveObjectInspector oi) {
double result = 0;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = 0;
break;
case BOOLEAN:
result = (((BooleanObjectInspector) oi).get(o) ? 1 : 0);
break;
case BYTE:
result = ((ByteObjectInspector) oi).get(o);
break;
case SHORT:
result = ((ShortObjectInspector) oi).get(o);
break;
case INT:
result = ((IntObjectInspector) oi).get(o);
break;
case LONG:
result = ((LongObjectInspector) oi).get(o);
break;
case FLOAT:
result = ((FloatObjectInspector) oi).get(o);
break;
case DOUBLE:
result = ((DoubleObjectInspector) oi).get(o);
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
String s = soi.getPrimitiveJavaObject(o);
result = Double.parseDouble(s);
break;
case VARCHAR:
result = Double.parseDouble(getString(o, oi));
break;
case TIMESTAMP:
result = ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o).getDouble();
break;
case DECIMAL:
result = ((HiveDecimalObjectInspector) oi)
.getPrimitiveJavaObject(o).doubleValue();
break;
case DATE:  // unsupported conversion
default:
throw new RuntimeException("Hive 2 Internal error: unsupported conversion from type: "
+ oi.getTypeName());
}
return result;
}

/**
* Get the float value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* NumberFormatException will be thrown if o is not a valid number.
*/
public static float getFloat(Object o, PrimitiveObjectInspector oi) {
return (float) getDouble(o, oi);
}

/**
* Get the String value out of a primitive object. Note that
* NullPointerException will be thrown if o is null. Note that
* RuntimeException will be thrown if o is not a valid string.
*/
public static String getString(Object o, PrimitiveObjectInspector oi) {

if (o == null) {
return null;
}

String result = null;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = null;
break;
case BOOLEAN:
result = String.valueOf((((BooleanObjectInspector) oi).get(o)));
break;
case BYTE:
result = String.valueOf((((ByteObjectInspector) oi).get(o)));
break;
case SHORT:
result = String.valueOf((((ShortObjectInspector) oi).get(o)));
break;
case INT:
result = String.valueOf((((IntObjectInspector) oi).get(o)));
break;
case LONG:
result = String.valueOf((((LongObjectInspector) oi).get(o)));
break;
case FLOAT:
result = String.valueOf((((FloatObjectInspector) oi).get(o)));
break;
case DOUBLE:
result = String.valueOf((((DoubleObjectInspector) oi).get(o)));
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
result = soi.getPrimitiveJavaObject(o);
break;
case VARCHAR:
HiveVarcharObjectInspector hcoi = (HiveVarcharObjectInspector) oi;
result = hcoi.getPrimitiveJavaObject(o).toString();
break;
case DATE:
result = ((DateObjectInspector) oi).getPrimitiveWritableObject(o).toString();
break;
case TIMESTAMP:
result = ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o).toString();
break;
case DECIMAL:
result = ((HiveDecimalObjectInspector) oi)
.getPrimitiveJavaObject(o).toString();
break;
default:
throw new RuntimeException("Hive 2 Internal error: unknown type: "
+ oi.getTypeName());
}
return result;
}

public static HiveVarchar getHiveVarchar(Object o, PrimitiveObjectInspector oi) {

if (o == null) {
return null;
}

HiveVarchar result = null;
switch (oi.getPrimitiveCategory()) {
case VARCHAR:
result = ((HiveVarcharObjectInspector)oi).getPrimitiveJavaObject(o);
break;
default:
// Is there a way to provide char length here?
// It might actually be ok as long as there is an object inspector (with char length)
// receiving this value.
result = new HiveVarchar();
result.setValue(getString(o, oi));
break;
}
return result;
}

public static BytesWritable getBinary(Object o, PrimitiveObjectInspector oi) {

if (null == o) {
return null;
}

switch (oi.getPrimitiveCategory()) {

case VOID:
return null;

case STRING:
Text text = ((StringObjectInspector) oi).getPrimitiveWritableObject(o);
BytesWritable bw = new BytesWritable();
bw.set(text.getBytes(), 0, text.getLength());
return bw;

case BINARY:
return ((BinaryObjectInspector) oi).getPrimitiveWritableObject(o);

default:
throw new RuntimeException("Cannot convert to Binary from: "
+ oi.getTypeName());
}
}

public static HiveDecimal getHiveDecimal(Object o, PrimitiveObjectInspector oi) {
if (o == null) {
return null;
}

HiveDecimal result = null;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = null;
break;
case BOOLEAN:
result = ((BooleanObjectInspector) oi).get(o) ?
HiveDecimal.ONE : HiveDecimal.ZERO;
break;
case BYTE:
result = new HiveDecimal(((ByteObjectInspector) oi).get(o));
break;
case SHORT:
result = new HiveDecimal(((ShortObjectInspector) oi).get(o));
break;
case INT:
result = new HiveDecimal(((IntObjectInspector) oi).get(o));
break;
case LONG:
result = new HiveDecimal(((LongObjectInspector) oi).get(o));
break;
case FLOAT:
Float f = ((FloatObjectInspector) oi).get(o);
result = new HiveDecimal(f.toString());
break;
case DOUBLE:
Double d = ((DoubleObjectInspector) oi).get(o);
result = new HiveDecimal(d.toString());
break;
case STRING:
result = new HiveDecimal(((StringObjectInspector) oi).getPrimitiveJavaObject(o));
break;
case VARCHAR:
result = new HiveDecimal(getString(o, oi));
break;
case TIMESTAMP:
Double ts = ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o)
.getDouble();
result = new HiveDecimal(ts.toString());
break;
case DECIMAL:
result = ((HiveDecimalObjectInspector) oi).getPrimitiveJavaObject(o);
break;
case DATE:  // unsupported conversion
default:
throw new RuntimeException("Hive 2 Internal error: unsupported conversion from type: "
+ oi.getTypeName());
}
return result;
}

public static Date getDate(Object o, PrimitiveObjectInspector oi) {
if (o == null) {
return null;
}

Date result = null;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = null;
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
String s = soi.getPrimitiveJavaObject(o).trim();
try {
result = Date.valueOf(s);
} catch (IllegalArgumentException e) {
result = null;
}
break;
case VARCHAR: {
try {
String val = getString(o, oi).trim();
result = Date.valueOf(val);
} catch (IllegalArgumentException e) {
result = null;
}
break;
}
case DATE:
result = ((DateObjectInspector) oi).getPrimitiveWritableObject(o).get();
break;
case TIMESTAMP:
result = DateWritable.timeToDate(
((TimestampObjectInspector) oi).getPrimitiveWritableObject(o).getSeconds());
break;
default:
throw new RuntimeException("Cannot convert to Date from: "
+ oi.getTypeName());
}

return result;
}

public static Timestamp getTimestamp(Object o, PrimitiveObjectInspector oi) {
if (o == null) {
return null;
}

Timestamp result = null;
switch (oi.getPrimitiveCategory()) {
case VOID:
result = null;
break;
case BOOLEAN:
result = new Timestamp(((BooleanObjectInspector) oi).get(o) ? 1 : 0);
break;
case BYTE:
result = new Timestamp(((ByteObjectInspector) oi).get(o));
break;
case SHORT:
result = new Timestamp(((ShortObjectInspector) oi).get(o));
break;
case INT:
result = new Timestamp(((IntObjectInspector) oi).get(o));
break;
case LONG:
result = new Timestamp(((LongObjectInspector) oi).get(o));
break;
case FLOAT:
result = TimestampWritable.floatToTimestamp(((FloatObjectInspector) oi).get(o));
break;
case DOUBLE:
result = TimestampWritable.doubleToTimestamp(((DoubleObjectInspector) oi).get(o));
break;
case DECIMAL:
result = TimestampWritable.decimalToTimestamp(((HiveDecimalObjectInspector) oi)
.getPrimitiveJavaObject(o));
break;
case STRING:
StringObjectInspector soi = (StringObjectInspector) oi;
String s = soi.getPrimitiveJavaObject(o);
result = getTimestampFromString(s);
break;
case VARCHAR:
result = getTimestampFromString(getString(o, oi));
break;
case DATE:
result = new Timestamp(
((DateObjectInspector) oi).getPrimitiveWritableObject(o).get().getTime());
break;
case TIMESTAMP:
result = ((TimestampObjectInspector) oi).getPrimitiveWritableObject(o).getTimestamp();
break;
default:
throw new RuntimeException("Hive 2 Internal error: unknown type: "
+ oi.getTypeName());
}
return result;
}

static Timestamp getTimestampFromString(String s) {
Timestamp result;
s = s.trim();

// Throw away extra if more than 9 decimal places
int periodIdx = s.indexOf(".");
if (periodIdx != -1) {
if (s.length() - periodIdx > 9) {
s = s.substring(0, periodIdx + 10);
}
}
try {
result = Timestamp.valueOf(s);
} catch (IllegalArgumentException e) {
result = null;
}
return result;
}

public static Class<?> getJavaPrimitiveClassFromObjectInspector(ObjectInspector oi) {
if (oi.getCategory() != Category.PRIMITIVE) {
return null;
}
PrimitiveObjectInspector poi = (PrimitiveObjectInspector) oi;
PrimitiveTypeEntry t =
getTypeEntryFromPrimitiveCategory(poi.getPrimitiveCategory());
return t == null ? null : t.primitiveJavaClass;
}

/**
* Provide a general grouping for each primitive data type.
*/
public static enum PrimitiveGrouping {
NUMERIC_GROUP, STRING_GROUP, BOOLEAN_GROUP, DATE_GROUP, BINARY_GROUP,
VOID_GROUP, UNKNOWN_GROUP
};

/**
* Based on the PrimitiveCategory of a type, return the PrimitiveGrouping
* that the PrimitiveCategory belongs to (numeric, string, date, etc).
* @param primitiveCategory Primitive category of the type
* @return PrimitveGrouping corresponding to the PrimitiveCategory,
*         or UNKNOWN_GROUP if the type does not match to a grouping.
*/
public static PrimitiveGrouping getPrimitiveGrouping(PrimitiveCategory primitiveCategory) {
switch (primitiveCategory) {
case BYTE:
case SHORT:
case INT:
case LONG:
case FLOAT:
case DOUBLE:
case DECIMAL:
return PrimitiveGrouping.NUMERIC_GROUP;
case STRING:
case VARCHAR:
return PrimitiveGrouping.STRING_GROUP;
case BOOLEAN:
return PrimitiveGrouping.BOOLEAN_GROUP;
case TIMESTAMP:
case DATE:
return PrimitiveGrouping.DATE_GROUP;
case BINARY:
return PrimitiveGrouping.BINARY_GROUP;
case VOID:
return PrimitiveGrouping.VOID_GROUP;
default:
return PrimitiveGrouping.UNKNOWN_GROUP;
}
}

private PrimitiveObjectInspectorUtils() {
// prevent instantiation
}

/**
* Helper class to store parameterized primitive object inspectors, which can be
* used by the various object inspector factory methods.
*/
public static class ParameterizedObjectInspectorMap {
HashMap<PrimitiveCategory, HashMap<String, PrimitiveObjectInspector>> entries;

public ParameterizedObjectInspectorMap() {
entries =
new HashMap<PrimitiveCategory, HashMap<String, PrimitiveObjectInspector>>();
}

public PrimitiveObjectInspector getObjectInspector(
PrimitiveTypeSpec typeSpec) {
PrimitiveCategory category = typeSpec.getPrimitiveCategory();
BaseTypeParams params = typeSpec.getTypeParams();
HashMap<String, PrimitiveObjectInspector> entriesForCategory = entries.get(category);
if (entriesForCategory == null) {
return null;
}
return (PrimitiveObjectInspector)entriesForCategory.get(params.toString());
}

public void setObjectInspector(PrimitiveObjectInspector oi) {
PrimitiveCategory category = oi.getPrimitiveCategory();
HashMap<String, PrimitiveObjectInspector> entriesForCategory = entries.get(category);
if (entriesForCategory == null) {
entriesForCategory = new HashMap<String, PrimitiveObjectInspector>();
entries.put(category, entriesForCategory);
}
entriesForCategory.put(oi.getTypeParams().toString(), oi);
}
}
}