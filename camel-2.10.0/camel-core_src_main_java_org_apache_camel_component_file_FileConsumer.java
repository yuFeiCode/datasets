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
package org.apache.camel.component.file;

import java.io.File;
import java.util.List;

import org.apache.camel.Processor;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.ObjectHelper;

/**
* File consumer.
*/
public class FileConsumer extends GenericFileConsumer<File> {

private String endpointPath;

public FileConsumer(GenericFileEndpoint<File> endpoint, Processor processor, GenericFileOperations<File> operations) {
super(endpoint, processor, operations);
this.endpointPath = endpoint.getConfiguration().getDirectory();
}

@Override
protected boolean pollDirectory(String fileName, List<GenericFile<File>> fileList, int depth) {
log.trace("pollDirectory from fileName: {}", fileName);

depth++;

File directory = new File(fileName);
if (!directory.exists() || !directory.isDirectory()) {
log.debug("Cannot poll as directory does not exists or its not a directory: {}", directory);
if (getEndpoint().isDirectoryMustExist()) {
throw new GenericFileOperationFailedException("Directory does not exist: " + directory);
}
return true;
}

log.trace("Polling directory: {}", directory.getPath());
File[] files = directory.listFiles();
if (files == null || files.length == 0) {
// no files in this directory to poll
if (log.isTraceEnabled()) {
log.trace("No files found in directory: {}", directory.getPath());
}
return true;
} else {
// we found some files
if (log.isTraceEnabled()) {
log.trace("Found {} in directory: {}", files.length, directory.getPath());
}
}

for (File file : files) {
// check if we can continue polling in files
if (!canPollMoreFiles(fileList)) {
return false;
}

// trace log as Windows/Unix can have different views what the file is?
if (log.isTraceEnabled()) {
log.trace("Found file: {} [isAbsolute: {}, isDirectory: {}, isFile: {}, isHidden: {}]",
new Object[]{file, file.isAbsolute(), file.isDirectory(), file.isFile(), file.isHidden()});
}

// creates a generic file
GenericFile<File> gf = asGenericFile(endpointPath, file, getEndpoint().getCharset());

if (file.isDirectory()) {
if (endpoint.isRecursive() && isValidFile(gf, true) && depth < endpoint.getMaxDepth()) {
// recursive scan and add the sub files and folders
String subDirectory = fileName + File.separator + file.getName();
boolean canPollMore = pollDirectory(subDirectory, fileList, depth);
if (!canPollMore) {
return false;
}
}
} else {
// Windows can report false to a file on a share so regard it always as a file (if its not a directory)
if (isValidFile(gf, false) && depth >= endpoint.minDepth) {
if (isInProgress(gf)) {
if (log.isTraceEnabled()) {
log.trace("Skipping as file is already in progress: {}", gf.getFileName());
}
} else {
log.trace("Adding valid file: {}", file);
// matched file so add
fileList.add(gf);
}
}

}
}

return true;
}

/**
* Creates a new GenericFile<File> based on the given file.
*
* @param endpointPath the starting directory the endpoint was configured with
* @param file the source file
* @return wrapped as a GenericFile
*/
public static GenericFile<File> asGenericFile(String endpointPath, File file, String charset) {
GenericFile<File> answer = new GenericFile<File>();
// use file specific binding
answer.setBinding(new FileBinding());

answer.setCharset(charset);
answer.setEndpointPath(endpointPath);
answer.setFile(file);
answer.setFileNameOnly(file.getName());
answer.setFileLength(file.length());
answer.setDirectory(file.isDirectory());
// must use FileUtil.isAbsolute to have consistent check for whether the file is
// absolute or not. As windows do not consider \ paths as absolute where as all
// other OS platforms will consider \ as absolute. The logic in Camel mandates
// that we align this for all OS. That is why we must use FileUtil.isAbsolute
// to return a consistent answer for all OS platforms.
answer.setAbsolute(FileUtil.isAbsolute(file));
answer.setAbsoluteFilePath(file.getAbsolutePath());
answer.setLastModified(file.lastModified());

// compute the file path as relative to the starting directory
File path;
String endpointNormalized = FileUtil.normalizePath(endpointPath);
if (file.getPath().startsWith(endpointNormalized)) {
// skip duplicate endpoint path
path = new File(ObjectHelper.after(file.getPath(), endpointNormalized + File.separator));
} else {
path = new File(file.getPath());
}

if (path.getParent() != null) {
answer.setRelativeFilePath(path.getParent() + File.separator + file.getName());
} else {
answer.setRelativeFilePath(path.getName());
}

// the file name should be the relative path
answer.setFileName(answer.getRelativeFilePath());

// use file as body as we have converters if needed as stream
answer.setBody(file);
return answer;
}

@Override
public FileEndpoint getEndpoint() {
return (FileEndpoint) super.getEndpoint();
}
}