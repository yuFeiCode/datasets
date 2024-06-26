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

package org.apache.hive.service.cli.operation;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationState;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.session.HiveSession;

/**
* SQLOperation.
*
*/
public class SQLOperation extends ExecuteStatementOperation {

private Driver driver = null;
private CommandProcessorResponse response;
private TableSchema resultSchema = null;
private Schema mResultSchema = null;
private SerDe serde = null;
private final boolean runAsync;
private Future<?> backgroundHandle;

public SQLOperation(HiveSession parentSession, String statement, Map<String,
String> confOverlay, boolean runInBackground) {
// TODO: call setRemoteUser in ExecuteStatementOperation or higher.
super(parentSession, statement, confOverlay);
this.runAsync = runInBackground;
}


public void prepare() throws HiveSQLException {
}

private void runInternal() throws HiveSQLException {
setState(OperationState.RUNNING);
String statement_trimmed = statement.trim();
String[] tokens = statement_trimmed.split("\\s");
String cmd_1 = statement_trimmed.substring(tokens[0].length()).trim();

int ret = 0;
String errorMessage = "";
String SQLState = null;

try {
driver = new Driver(getParentSession().getHiveConf());
// In Hive server mode, we are not able to retry in the FetchTask
// case, when calling fetch queries since execute() has returned.
// For now, we disable the test attempts.
driver.setTryCount(Integer.MAX_VALUE);

String subStatement = new VariableSubstitution().substitute(getParentSession().getHiveConf(), statement);

response = driver.run(subStatement);
if (0 != response.getResponseCode()) {
throw new HiveSQLException("Error while processing statement: "
+ response.getErrorMessage(), response.getSQLState(), response.getResponseCode());
}

mResultSchema = driver.getSchema();

// hasResultSet should be true only if the query has a FetchTask
// "explain" is an exception for now
if(driver.getPlan().getFetchTask() != null) {
//Schema has to be set
if (mResultSchema == null || !mResultSchema.isSetFieldSchemas()) {
throw new HiveSQLException("Error running query: Schema and FieldSchema " +
"should be set when query plan has a FetchTask");
}
resultSchema = new TableSchema(mResultSchema);
setHasResultSet(true);
} else {
setHasResultSet(false);
}
// Set hasResultSet true if the plan has ExplainTask
// TODO explain should use a FetchTask for reading
for (Task<? extends Serializable> task: driver.getPlan().getRootTasks()) {
if (task.getClass() == ExplainTask.class) {
setHasResultSet(true);
break;
}
}
} catch (HiveSQLException e) {
setState(OperationState.ERROR);
throw e;
} catch (Exception e) {
setState(OperationState.ERROR);
throw new HiveSQLException("Error running query: " + e.toString(), e);
}
setState(OperationState.FINISHED);
}

@Override
public void run() throws HiveSQLException {
setState(OperationState.PENDING);
if (!shouldRunAsync()) {
runInternal();
} else {
Runnable backgroundOperation = new Runnable() {
SessionState ss = SessionState.get();
@Override
public void run() {
SessionState.start(ss);
try {
runInternal();
} catch (HiveSQLException e) {
LOG.error("Error: ", e);
// TODO: Return a more detailed error to the client,
// currently the async thread only writes to the log and sets the OperationState
}
}
};
try {
// This submit blocks if no background threads are available to run this operation
backgroundHandle =
getParentSession().getSessionManager().submitBackgroundOperation(backgroundOperation);
} catch (RejectedExecutionException rejected) {
setState(OperationState.ERROR);
throw new HiveSQLException(rejected);
}
}
}

private void cleanup(OperationState state) throws HiveSQLException {
setState(state);
if (shouldRunAsync()) {
if (backgroundHandle != null) {
backgroundHandle.cancel(true);
}
}
if (driver != null) {
driver.close();
driver.destroy();
}

SessionState ss = SessionState.get();
if (ss.getTmpOutputFile() != null) {
ss.getTmpOutputFile().delete();
}
}

@Override
public void cancel() throws HiveSQLException {
cleanup(OperationState.CANCELED);
}

@Override
public void close() throws HiveSQLException {
cleanup(OperationState.CLOSED);
}

@Override
public TableSchema getResultSetSchema() throws HiveSQLException {
assertState(OperationState.FINISHED);
if (resultSchema == null) {
resultSchema = new TableSchema(driver.getSchema());
}
return resultSchema;
}


@Override
public RowSet getNextRowSet(FetchOrientation orientation, long maxRows) throws HiveSQLException {
assertState(OperationState.FINISHED);
ArrayList<String> rows = new ArrayList<String>();
driver.setMaxRows((int)maxRows);

try {
driver.getResults(rows);

getSerDe();
StructObjectInspector soi = (StructObjectInspector) serde.getObjectInspector();
List<? extends StructField> fieldRefs = soi.getAllStructFieldRefs();
RowSet rowSet = new RowSet();

Object[] deserializedFields = new Object[fieldRefs.size()];
Object rowObj;
ObjectInspector fieldOI;

for (String rowString : rows) {
rowObj = serde.deserialize(new BytesWritable(rowString.getBytes()));
for (int i = 0; i < fieldRefs.size(); i++) {
StructField fieldRef = fieldRefs.get(i);
fieldOI = fieldRef.getFieldObjectInspector();
deserializedFields[i] = convertLazyToJava(soi.getStructFieldData(rowObj, fieldRef), fieldOI);
}
rowSet.addRow(resultSchema, deserializedFields);
}
return rowSet;
} catch (IOException e) {
throw new HiveSQLException(e);
} catch (CommandNeedRetryException e) {
throw new HiveSQLException(e);
} catch (Exception e) {
throw new HiveSQLException(e);
}
}

/**
* Convert a LazyObject to a standard Java object in compliance with JDBC 3.0 (see JDBC 3.0
* Specification, Table B-3: Mapping from JDBC Types to Java Object Types).
*
* This method is kept consistent with {@link HiveResultSetMetaData#hiveTypeToSqlType}.
*/
private static Object convertLazyToJava(Object o, ObjectInspector oi) {
Object obj = ObjectInspectorUtils.copyToStandardObject(o, oi, ObjectInspectorCopyOption.JAVA);

if (obj == null) {
return null;
}
if(oi.getTypeName().equals(serdeConstants.BINARY_TYPE_NAME)) {
return new String((byte[])obj);
}
// for now, expose non-primitive as a string
// TODO: expose non-primitive as a structured object while maintaining JDBC compliance
if (oi.getCategory() != ObjectInspector.Category.PRIMITIVE) {
return SerDeUtils.getJSONString(o, oi);
}
return obj;
}


private SerDe getSerDe() throws SQLException {
if (serde != null) {
return serde;
}
try {
List<FieldSchema> fieldSchemas = mResultSchema.getFieldSchemas();
List<String> columnNames = new ArrayList<String>();
List<String> columnTypes = new ArrayList<String>();
StringBuilder namesSb = new StringBuilder();
StringBuilder typesSb = new StringBuilder();

if (fieldSchemas != null && !fieldSchemas.isEmpty()) {
for (int pos = 0; pos < fieldSchemas.size(); pos++) {
if (pos != 0) {
namesSb.append(",");
typesSb.append(",");
}
columnNames.add(fieldSchemas.get(pos).getName());
columnTypes.add(fieldSchemas.get(pos).getType());
namesSb.append(fieldSchemas.get(pos).getName());
typesSb.append(fieldSchemas.get(pos).getType());
}
}
String names = namesSb.toString();
String types = typesSb.toString();

serde = new LazySimpleSerDe();
Properties props = new Properties();
if (names.length() > 0) {
LOG.debug("Column names: " + names);
props.setProperty(serdeConstants.LIST_COLUMNS, names);
}
if (types.length() > 0) {
LOG.debug("Column types: " + types);
props.setProperty(serdeConstants.LIST_COLUMN_TYPES, types);
}
serde.initialize(new HiveConf(), props);

} catch (Exception ex) {
ex.printStackTrace();
throw new SQLException("Could not create ResultSet: " + ex.getMessage(), ex);
}
return serde;
}

private boolean shouldRunAsync() {
return runAsync;
}

}