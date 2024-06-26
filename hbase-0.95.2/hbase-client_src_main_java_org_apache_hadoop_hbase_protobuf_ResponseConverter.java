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
package org.apache.hadoop.hbase.protobuf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.protobuf.generated.AccessControlProtos.UserPermissionsResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CloseRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetOnlineRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetServerInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.OpenRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.RollWALWriterResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.ServerInfo;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ActionResult;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ResultCellMeta;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.NameBytesPair;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.CatalogScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterAdminProtos.EnableCatalogJanitorResponse;
import org.apache.hadoop.hbase.protobuf.generated.RegionServerStatusProtos.GetLastFlushedSequenceIdResponse;
import org.apache.hadoop.hbase.regionserver.RegionOpeningState;
import org.apache.hadoop.hbase.security.access.UserPermission;
import org.apache.hadoop.util.StringUtils;

import com.google.protobuf.ByteString;
import com.google.protobuf.RpcController;

/**
* Helper utility to build protocol buffer responses,
* or retrieve data from protocol buffer responses.
*/
@InterfaceAudience.Private
public final class ResponseConverter {
public static final Log LOG = LogFactory.getLog(ResponseConverter.class);

private ResponseConverter() {
}

// Start utilities for Client

/**
* Get the results from a protocol buffer MultiResponse
*
* @param proto the protocol buffer MultiResponse to convert
* @param cells Cells to go with the passed in <code>proto</code>.  Can be null.
* @return the results that were in the MultiResponse (a Result or an Exception).
* @throws IOException
*/
public static List<Object> getResults(final ClientProtos.MultiResponse proto,
final CellScanner cells)
throws IOException {
List<Object> results = new ArrayList<Object>();
List<ActionResult> resultList = proto.getResultList();
for (int i = 0, n = resultList.size(); i < n; i++) {
ActionResult result = resultList.get(i);
if (result.hasException()) {
results.add(ProtobufUtil.toException(result.getException()));
} else if (result.hasValue()) {
ClientProtos.Result value = result.getValue();
results.add(ProtobufUtil.toResult(value, cells));
} else {
results.add(new Result());
}
}
return results;
}

/**
* Wrap a throwable to an action result.
*
* @param t
* @return an action result
*/
public static ActionResult buildActionResult(final Throwable t) {
ActionResult.Builder builder = ActionResult.newBuilder();
NameBytesPair.Builder parameterBuilder = NameBytesPair.newBuilder();
parameterBuilder.setName(t.getClass().getName());
parameterBuilder.setValue(
ByteString.copyFromUtf8(StringUtils.stringifyException(t)));
builder.setException(parameterBuilder.build());
return builder.build();
}

/**
* Converts the permissions list into a protocol buffer UserPermissionsResponse
*/
public static UserPermissionsResponse buildUserPermissionsResponse(
final List<UserPermission> permissions) {
UserPermissionsResponse.Builder builder = UserPermissionsResponse.newBuilder();
for (UserPermission perm : permissions) {
builder.addUserPermission(ProtobufUtil.toUserPermission(perm));
}
return builder.build();
}

// End utilities for Client
// Start utilities for Admin

/**
* Get the list of regions to flush from a RollLogWriterResponse
*
* @param proto the RollLogWriterResponse
* @return the the list of regions to flush
*/
public static byte[][] getRegions(final RollWALWriterResponse proto) {
if (proto == null || proto.getRegionToFlushCount() == 0) return null;
List<byte[]> regions = new ArrayList<byte[]>();
for (ByteString region: proto.getRegionToFlushList()) {
regions.add(region.toByteArray());
}
return (byte[][])regions.toArray();
}

/**
* Get the list of region info from a GetOnlineRegionResponse
*
* @param proto the GetOnlineRegionResponse
* @return the list of region info
*/
public static List<HRegionInfo> getRegionInfos(final GetOnlineRegionResponse proto) {
if (proto == null || proto.getRegionInfoCount() == 0) return null;
return ProtobufUtil.getRegionInfos(proto);
}

/**
* Get the region opening state from a OpenRegionResponse
*
* @param proto the OpenRegionResponse
* @return the region opening state
*/
public static RegionOpeningState getRegionOpeningState
(final OpenRegionResponse proto) {
if (proto == null || proto.getOpeningStateCount() != 1) return null;
return RegionOpeningState.valueOf(
proto.getOpeningState(0).name());
}

/**
* Get a list of region opening state from a OpenRegionResponse
*
* @param proto the OpenRegionResponse
* @return the list of region opening state
*/
public static List<RegionOpeningState> getRegionOpeningStateList(
final OpenRegionResponse proto) {
if (proto == null) return null;
List<RegionOpeningState> regionOpeningStates = new ArrayList<RegionOpeningState>();
for (int i = 0; i < proto.getOpeningStateCount(); i++) {
regionOpeningStates.add(RegionOpeningState.valueOf(
proto.getOpeningState(i).name()));
}
return regionOpeningStates;
}

/**
* Check if the region is closed from a CloseRegionResponse
*
* @param proto the CloseRegionResponse
* @return the region close state
*/
public static boolean isClosed
(final CloseRegionResponse proto) {
if (proto == null || !proto.hasClosed()) return false;
return proto.getClosed();
}

/**
* A utility to build a GetServerInfoResponse.
*
* @param serverName
* @param webuiPort
* @return the response
*/
public static GetServerInfoResponse buildGetServerInfoResponse(
final ServerName serverName, final int webuiPort) {
GetServerInfoResponse.Builder builder = GetServerInfoResponse.newBuilder();
ServerInfo.Builder serverInfoBuilder = ServerInfo.newBuilder();
serverInfoBuilder.setServerName(ProtobufUtil.toServerName(serverName));
if (webuiPort >= 0) {
serverInfoBuilder.setWebuiPort(webuiPort);
}
builder.setServerInfo(serverInfoBuilder.build());
return builder.build();
}

/**
* A utility to build a GetOnlineRegionResponse.
*
* @param regions
* @return the response
*/
public static GetOnlineRegionResponse buildGetOnlineRegionResponse(
final List<HRegionInfo> regions) {
GetOnlineRegionResponse.Builder builder = GetOnlineRegionResponse.newBuilder();
for (HRegionInfo region: regions) {
builder.addRegionInfo(HRegionInfo.convert(region));
}
return builder.build();
}

/**
* Creates a response for the catalog scan request
* @return A CatalogScanResponse
*/
public static CatalogScanResponse buildCatalogScanResponse(int numCleaned) {
return CatalogScanResponse.newBuilder().setScanResult(numCleaned).build();
}

/**
* Creates a response for the catalog scan request
* @return A EnableCatalogJanitorResponse
*/
public static EnableCatalogJanitorResponse buildEnableCatalogJanitorResponse(boolean prevValue) {
return EnableCatalogJanitorResponse.newBuilder().setPrevValue(prevValue).build();
}

// End utilities for Admin

/**
* Creates a response for the last flushed sequence Id request
* @return A GetLastFlushedSequenceIdResponse
*/
public static GetLastFlushedSequenceIdResponse buildGetLastFlushedSequenceIdResponse(
long seqId) {
return GetLastFlushedSequenceIdResponse.newBuilder().setLastFlushedSequenceId(seqId).build();
}

/**
* Stores an exception encountered during RPC invocation so it can be passed back
* through to the client.
* @param controller the controller instance provided by the client when calling the service
* @param ioe the exception encountered
*/
public static void setControllerException(RpcController controller, IOException ioe) {
if (controller != null) {
if (controller instanceof ServerRpcController) {
((ServerRpcController)controller).setFailedOn(ioe);
} else {
controller.setFailed(StringUtils.stringifyException(ioe));
}
}
}

/**
* Create Results from the cells using the cells meta data.
* @param cellScanner
* @param response
* @return results
*/
public static Result[] getResults(CellScanner cellScanner, ScanResponse response)
throws IOException {
if (response == null || cellScanner == null) return null;
ResultCellMeta resultCellMeta = response.getResultCellMeta();
if (resultCellMeta == null) return null;
int noOfResults = resultCellMeta.getCellsLengthCount();
Result[] results = new Result[noOfResults];
for (int i = 0; i < noOfResults; i++) {
int noOfCells = resultCellMeta.getCellsLength(i);
List<Cell> cells = new ArrayList<Cell>(noOfCells);
for (int j = 0; j < noOfCells; j++) {
try {
if (cellScanner.advance() == false) {
// We are not able to retrieve the exact number of cells which ResultCellMeta says us.
// We have to scan for the same results again. Throwing DNRIOE as a client retry on the
// same scanner will result in OutOfOrderScannerNextException
String msg = "Results sent from server=" + noOfResults + ". But only got " + i
+ " results completely at client. Resetting the scanner to scan again.";
LOG.error(msg);
throw new DoNotRetryIOException(msg);
}
} catch (IOException ioe) {
// We are getting IOE while retrieving the cells for Results.
// We have to scan for the same results again. Throwing DNRIOE as a client retry on the
// same scanner will result in OutOfOrderScannerNextException
LOG.error("Exception while reading cells from result."
+ "Resetting the scanner to scan again.", ioe);
throw new DoNotRetryIOException("Resetting the scanner.", ioe);
}
cells.add(cellScanner.current());
}
results[i] = new Result(cells);
}
return results;
}
}