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

package org.apache.hadoop.hbase.thrift;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.thrift.generated.ColumnDescriptor;
import org.apache.hadoop.hbase.thrift.generated.IllegalArgument;
import org.apache.hadoop.hbase.thrift.generated.TCell;
import org.apache.hadoop.hbase.thrift.generated.TIncrement;
import org.apache.hadoop.hbase.thrift.generated.TColumn;
import org.apache.hadoop.hbase.thrift.generated.TRowResult;
import org.apache.hadoop.hbase.util.Bytes;

@InterfaceAudience.Private
public class ThriftUtilities {

/**
* This utility method creates a new Hbase HColumnDescriptor object based on a
* Thrift ColumnDescriptor "struct".
*
* @param in
*          Thrift ColumnDescriptor object
* @return HColumnDescriptor
* @throws IllegalArgument
*/
static public HColumnDescriptor colDescFromThrift(ColumnDescriptor in)
throws IllegalArgument {
Compression.Algorithm comp =
Compression.getCompressionAlgorithmByName(in.compression.toLowerCase());
BloomType bt =
BloomType.valueOf(in.bloomFilterType);

if (in.name == null || !in.name.hasRemaining()) {
throw new IllegalArgument("column name is empty");
}
byte [] parsedName = KeyValue.parseColumn(Bytes.getBytes(in.name))[0];
HColumnDescriptor col = new HColumnDescriptor(parsedName)
.setMaxVersions(in.maxVersions)
.setCompressionType(comp)
.setInMemory(in.inMemory)
.setBlockCacheEnabled(in.blockCacheEnabled)
.setTimeToLive(in.timeToLive)
.setBloomFilterType(bt);
return col;
}

/**
* This utility method creates a new Thrift ColumnDescriptor "struct" based on
* an Hbase HColumnDescriptor object.
*
* @param in
*          Hbase HColumnDescriptor object
* @return Thrift ColumnDescriptor
*/
static public ColumnDescriptor colDescFromHbase(HColumnDescriptor in) {
ColumnDescriptor col = new ColumnDescriptor();
col.name = ByteBuffer.wrap(Bytes.add(in.getName(), KeyValue.COLUMN_FAMILY_DELIM_ARRAY));
col.maxVersions = in.getMaxVersions();
col.compression = in.getCompression().toString();
col.inMemory = in.isInMemory();
col.blockCacheEnabled = in.isBlockCacheEnabled();
col.bloomFilterType = in.getBloomFilterType().toString();
return col;
}

/**
* This utility method creates a list of Thrift TCell "struct" based on
* an Hbase Cell object. The empty list is returned if the input is null.
*
* @param in
*          Hbase Cell object
* @return Thrift TCell array
*/
static public List<TCell> cellFromHBase(KeyValue in) {
List<TCell> list = new ArrayList<TCell>(1);
if (in != null) {
list.add(new TCell(ByteBuffer.wrap(in.getValue()), in.getTimestamp()));
}
return list;
}

/**
* This utility method creates a list of Thrift TCell "struct" based on
* an Hbase Cell array. The empty list is returned if the input is null.
* @param in Hbase Cell array
* @return Thrift TCell array
*/
static public List<TCell> cellFromHBase(KeyValue[] in) {
List<TCell> list = null;
if (in != null) {
list = new ArrayList<TCell>(in.length);
for (int i = 0; i < in.length; i++) {
list.add(new TCell(ByteBuffer.wrap(in[i].getValue()), in[i].getTimestamp()));
}
} else {
list = new ArrayList<TCell>(0);
}
return list;
}

/**
* This utility method creates a list of Thrift TRowResult "struct" based on
* an Hbase RowResult object. The empty list is returned if the input is
* null.
*
* @param in
*          Hbase RowResult object
* @param sortColumns
*          This boolean dictates if row data is returned in a sorted order
*          sortColumns = True will set TRowResult's sortedColumns member
*                        which is an ArrayList of TColumn struct
*          sortColumns = False will set TRowResult's columns member which is
*                        a map of columnName and TCell struct
* @return Thrift TRowResult array
*/
static public List<TRowResult> rowResultFromHBase(Result[] in, boolean sortColumns) {
List<TRowResult> results = new ArrayList<TRowResult>();
for ( Result result_ : in) {
if(result_ == null || result_.isEmpty()) {
continue;
}
TRowResult result = new TRowResult();
result.row = ByteBuffer.wrap(result_.getRow());
if (sortColumns) {
result.sortedColumns = new ArrayList<TColumn>();
for (KeyValue kv : result_.raw()) {
result.sortedColumns.add(new TColumn(
ByteBuffer.wrap(KeyValue.makeColumn(kv.getFamily(),
kv.getQualifier())),
new TCell(ByteBuffer.wrap(kv.getValue()), kv.getTimestamp())));
}
} else {
result.columns = new TreeMap<ByteBuffer, TCell>();
for (KeyValue kv : result_.raw()) {
result.columns.put(
ByteBuffer.wrap(KeyValue.makeColumn(kv.getFamily(),
kv.getQualifier())),
new TCell(ByteBuffer.wrap(kv.getValue()), kv.getTimestamp()));
}
}
results.add(result);
}
return results;
}

/**
* This utility method creates a list of Thrift TRowResult "struct" based on
* an array of Hbase RowResult objects. The empty list is returned if the input is
* null.
*
* @param in
*          Array of Hbase RowResult objects
* @return Thrift TRowResult array
*/
static public List<TRowResult> rowResultFromHBase(Result[] in) {
return rowResultFromHBase(in, false);
}

static public List<TRowResult> rowResultFromHBase(Result in) {
Result [] result = { in };
return rowResultFromHBase(result);
}

/**
* From a {@link TIncrement} create an {@link Increment}.
* @param tincrement the Thrift version of an increment
* @return an increment that the {@link TIncrement} represented.
*/
public static Increment incrementFromThrift(TIncrement tincrement) {
Increment inc = new Increment(tincrement.getRow());
byte[][] famAndQf = KeyValue.parseColumn(tincrement.getColumn());
if (famAndQf.length <1 ) return null;
byte[] qual = famAndQf.length == 1 ? new byte[0]: famAndQf[1];
inc.addColumn(famAndQf[0], qual, tincrement.getAmmount());
return inc;
}
}