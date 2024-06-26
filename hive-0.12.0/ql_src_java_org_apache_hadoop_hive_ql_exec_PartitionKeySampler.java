/**
* Copyright 2010 The Apache Software Foundation
*
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

package org.apache.hadoop.hive.ql.exec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.io.HiveKey;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.FetchWork;
import org.apache.hadoop.hive.serde2.objectinspector.InspectableObject;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;

public class PartitionKeySampler implements OutputCollector<HiveKey, Object> {

public static final Comparator<byte[]> C = new Comparator<byte[]>() {
public final int compare(byte[] o1, byte[] o2) {
return WritableComparator.compareBytes(o1, 0, o1.length, o2, 0, o2.length);
}
};

private final List<byte[]> sampled = new ArrayList<byte[]>();

public void addSampleFile(Path inputPath, JobConf job) throws IOException {
FileSystem fs = inputPath.getFileSystem(job);
FSDataInputStream input = fs.open(inputPath);
try {
int count = input.readInt();
for (int i = 0; i < count; i++) {
byte[] key = new byte[input.readInt()];
input.readFully(key);
sampled.add(key);
}
} finally {
IOUtils.closeStream(input);
}
}

// keys from FetchSampler are collected here
public void collect(HiveKey key, Object value) throws IOException {
sampled.add(Arrays.copyOfRange(key.getBytes(), 0, key.getLength()));
}

// sort and pick partition keys
// copied from org.apache.hadoop.mapred.lib.InputSampler
private byte[][] getPartitionKeys(int numReduce) {
if (sampled.size() < numReduce - 1) {
throw new IllegalStateException("not enough number of sample");
}
byte[][] sorted = sampled.toArray(new byte[sampled.size()][]);
Arrays.sort(sorted, C);
byte[][] partitionKeys = new byte[numReduce - 1][];
float stepSize = sorted.length / (float) numReduce;
int last = -1;
for(int i = 1; i < numReduce; ++i) {
int k = Math.round(stepSize * i);
while (last >= k && C.compare(sorted[last], sorted[k]) == 0) {
k++;
}
if (k >= sorted.length) {
throw new IllegalStateException("not enough number of sample");
}
partitionKeys[i - 1] = sorted[k];
last = k;
}
return partitionKeys;
}

public void writePartitionKeys(Path path, JobConf job) throws IOException {
byte[][] partitionKeys = getPartitionKeys(job.getNumReduceTasks());

FileSystem fs = path.getFileSystem(job);
SequenceFile.Writer writer = SequenceFile.createWriter(fs, job, path,
BytesWritable.class, NullWritable.class);
try {
for (byte[] pkey : partitionKeys) {
BytesWritable wrapper = new BytesWritable(pkey);
writer.append(wrapper, NullWritable.get());
}
} finally {
IOUtils.closeStream(writer);
}
}

// random sampling
public static FetchSampler createSampler(FetchWork work, HiveConf conf, JobConf job,
Operator<?> operator) {
int sampleNum = conf.getIntVar(HiveConf.ConfVars.HIVESAMPLINGNUMBERFORORDERBY);
float samplePercent = conf.getFloatVar(HiveConf.ConfVars.HIVESAMPLINGPERCENTFORORDERBY);
if (samplePercent < 0.0 || samplePercent > 1.0) {
throw new RuntimeException("Percentile value must be within the range of 0 to 1.");
}
FetchSampler sampler = new FetchSampler(work, job, operator);
sampler.setSampleNum(sampleNum);
sampler.setSamplePercent(samplePercent);
return sampler;
}

private static class FetchSampler extends FetchOperator {

private int sampleNum = 1000;
private float samplePercent = 0.1f;
private final Random random = new Random();

private int sampled;

public FetchSampler(FetchWork work, JobConf job, Operator<?> operator) {
super(work, job, operator, null);
}

public void setSampleNum(int numSample) {
this.sampleNum = numSample;
}

public void setSamplePercent(float samplePercent) {
this.samplePercent = samplePercent;
}

@Override
public boolean pushRow() throws IOException, HiveException {
if (!super.pushRow()) {
return false;
}
if (sampled < sampleNum) {
return true;
}
operator.flush();
return false;
}

@Override
protected void pushRow(InspectableObject row) throws HiveException {
if (random.nextFloat() < samplePercent) {
sampled++;
super.pushRow(row);
}
}
}
}