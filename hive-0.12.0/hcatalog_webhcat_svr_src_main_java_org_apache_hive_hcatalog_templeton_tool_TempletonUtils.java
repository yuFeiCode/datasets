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
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.hive.hcatalog.templeton.tool;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.core.UriBuilder;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.util.StringUtils;
import org.apache.hive.hcatalog.templeton.UgiFactory;
import org.apache.hive.hcatalog.templeton.BadParam;

/**
* General utility methods.
*/
public class TempletonUtils {
/**
* Is the object non-empty?
*/
public static boolean isset(String s) {
return (s != null) && (s.length() > 0);
}

/**
* Is the object non-empty?
*/
public static boolean isset(char ch) {
return (ch != 0);
}

/**
* Is the object non-empty?
*/
public static <T> boolean isset(T[] a) {
return (a != null) && (a.length > 0);
}


/**
* Is the object non-empty?
*/
public static <T> boolean isset(Collection<T> col) {
return (col != null) && (!col.isEmpty());
}

/**
* Is the object non-empty?
*/
public static <K, V> boolean isset(Map<K, V> col) {
return (col != null) && (!col.isEmpty());
}


public static final Pattern JAR_COMPLETE
= Pattern.compile(" map \\d+%\\s+reduce \\d+%$");
public static final Pattern PIG_COMPLETE = Pattern.compile(" \\d+% complete$");

/**
* Extract the percent complete line from Pig or Jar jobs.
*/
public static String extractPercentComplete(String line) {
Matcher jar = JAR_COMPLETE.matcher(line);
if (jar.find())
return jar.group().trim();

Matcher pig = PIG_COMPLETE.matcher(line);
if (pig.find())
return pig.group().trim();

return null;
}

public static final Pattern JAR_ID = Pattern.compile(" Running job: (\\S+)$");
public static final Pattern PIG_ID = Pattern.compile(" HadoopJobId: (\\S+)$");
public static final Pattern[] ID_PATTERNS = {JAR_ID, PIG_ID};

/**
* Extract the job id from jar jobs.
*/
public static String extractChildJobId(String line) {
for (Pattern p : ID_PATTERNS) {
Matcher m = p.matcher(line);
if (m.find())
return m.group(1);
}

return null;
}

/**
* Take an array of strings and encode it into one string.
*/
public static String encodeArray(String[] plain) {
if (plain == null)
return null;

String[] escaped = new String[plain.length];

for (int i = 0; i < plain.length; ++i) {
if (plain[i] == null) {
plain[i] = "";
}
escaped[i] = StringUtils.escapeString(plain[i]);
}

return StringUtils.arrayToString(escaped);
}

/**
* Encode a List into a string.
*/
public static String encodeArray(List<String> list) {
if (list == null)
return null;
String[] array = new String[list.size()];
return encodeArray(list.toArray(array));
}

/**
* Take an encode strings and decode it into an array of strings.
*/
public static String[] decodeArray(String s) {
if (s == null)
return null;

String[] escaped = StringUtils.split(s);
String[] plain = new String[escaped.length];

for (int i = 0; i < escaped.length; ++i)
plain[i] = StringUtils.unEscapeString(escaped[i]);

return plain;
}

public static String[] hadoopFsListAsArray(String files, Configuration conf,
String user)
throws URISyntaxException, FileNotFoundException, IOException,
InterruptedException {
if (files == null || conf == null) {
return null;
}
String[] dirty = files.split(",");
String[] clean = new String[dirty.length];

for (int i = 0; i < dirty.length; ++i)
clean[i] = hadoopFsFilename(dirty[i], conf, user);

return clean;
}

public static String hadoopFsListAsString(String files, Configuration conf,
String user)
throws URISyntaxException, FileNotFoundException, IOException,
InterruptedException {
if (files == null || conf == null) {
return null;
}
return StringUtils.arrayToString(hadoopFsListAsArray(files, conf, user));
}

public static String hadoopFsFilename(String fname, Configuration conf, String user)
throws URISyntaxException, FileNotFoundException, IOException,
InterruptedException {
Path p = hadoopFsPath(fname, conf, user);
if (p == null)
return null;
else
return p.toString();
}

/**
* @return true iff we are sure the file is not there.
*/
public static boolean hadoopFsIsMissing(FileSystem fs, Path p) {
try {
return !fs.exists(p);
} catch (Throwable t) {
// Got an error, might be there anyway due to a
// permissions problem.
return false;
}
}

public static String addUserHomeDirectoryIfApplicable(String origPathStr, String user)
throws IOException, URISyntaxException {
URI uri = new URI(origPathStr);

if (uri.getPath().isEmpty()) {
String newPath = "/user/" + user;
uri = UriBuilder.fromUri(uri).replacePath(newPath).build();
} else if (!new Path(uri.getPath()).isAbsolute()) {
String newPath = "/user/" + user + "/" + uri.getPath();
uri = UriBuilder.fromUri(uri).replacePath(newPath).build();
} // no work needed for absolute paths

return uri.toString();
}

public static Path hadoopFsPath(String fname, final Configuration conf, String user)
throws URISyntaxException, IOException,
InterruptedException {
if (fname == null || conf == null) {
return null;
}

UserGroupInformation ugi;
if (user!=null) {
ugi = UgiFactory.getUgi(user);
} else {
ugi = UserGroupInformation.getLoginUser();
}
final String finalFName = new String(fname);

final FileSystem defaultFs =
ugi.doAs(new PrivilegedExceptionAction<FileSystem>() {
public FileSystem run()
throws URISyntaxException, IOException, InterruptedException {
return FileSystem.get(new URI(finalFName), conf);
}
});

fname = addUserHomeDirectoryIfApplicable(fname, user);
URI u = new URI(fname);
Path p = new Path(u).makeQualified(defaultFs);

if (hadoopFsIsMissing(defaultFs, p))
throw new FileNotFoundException("File " + fname + " does not exist.");

return p;
}

/**
* GET the given url.  Returns the number of bytes received.
*/
public static int fetchUrl(URL url)
throws IOException {
URLConnection cnx = url.openConnection();
InputStream in = cnx.getInputStream();

byte[] buf = new byte[8192];
int total = 0;
int len = 0;
while ((len = in.read(buf)) >= 0)
total += len;

return total;
}

/**
* Set the environment variables to specify the hadoop user.
*/
public static Map<String, String> hadoopUserEnv(String user,
String overrideClasspath) {
HashMap<String, String> env = new HashMap<String, String>();
env.put("HADOOP_USER_NAME", user);

if (overrideClasspath != null) {
env.put("HADOOP_USER_CLASSPATH_FIRST", "true");
String cur = System.getenv("HADOOP_CLASSPATH");
if (TempletonUtils.isset(cur))
overrideClasspath = overrideClasspath + ":" + cur;
env.put("HADOOP_CLASSPATH", overrideClasspath);
}

return env;
}

// Add double quotes around the given input parameter if it is not already
// quoted. Quotes are not allowed in the middle of the parameter, and
// BadParam exception is thrown if this is the case.
//
// This method should be used to escape parameters before they get passed to
// Windows cmd scripts (specifically, special characters like a comma or an
// equal sign might be lost as part of the cmd script processing if not
// under quotes).
public static String quoteForWindows(String param) throws BadParam {
if (Shell.WINDOWS) {
if (param != null && param.length() > 0) {
String nonQuotedPart = param;
boolean addQuotes = true;
if (param.charAt(0) == '\"' && param.charAt(param.length() - 1) == '\"') {
if (param.length() < 2)
throw new BadParam("Passed in parameter is incorrectly quoted: " + param);

addQuotes = false;
nonQuotedPart = param.substring(1, param.length() - 1);
}

// If we have any quotes other then the outside quotes, throw
if (nonQuotedPart.contains("\"")) {
throw new BadParam("Passed in parameter is incorrectly quoted: " + param);
}

if (addQuotes) {
param = '\"' + param + '\"';
}
}
}
return param;
}

public static void addCmdForWindows(ArrayList<String> args) {
if(Shell.WINDOWS){
args.add("cmd");
args.add("/c");
args.add("call");
}
}
}