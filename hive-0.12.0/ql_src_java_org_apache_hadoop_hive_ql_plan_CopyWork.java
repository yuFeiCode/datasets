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

package org.apache.hadoop.hive.ql.plan;

import java.io.Serializable;

/**
* CopyWork.
*
*/
@Explain(displayName = "Copy")
public class CopyWork implements Serializable {
private static final long serialVersionUID = 1L;
private String fromPath;
private String toPath;
private boolean errorOnSrcEmpty;

public CopyWork() {
}

public CopyWork(final String fromPath, final String toPath) {
this(fromPath, toPath, true);
}

public CopyWork(final String fromPath, final String toPath, boolean errorOnSrcEmpty) {
this.fromPath = fromPath;
this.toPath = toPath;
this.setErrorOnSrcEmpty(errorOnSrcEmpty);
}

@Explain(displayName = "source")
public String getFromPath() {
return fromPath;
}

public void setFromPath(final String fromPath) {
this.fromPath = fromPath;
}

@Explain(displayName = "destination")
public String getToPath() {
return toPath;
}

public void setToPath(final String toPath) {
this.toPath = toPath;
}

public void setErrorOnSrcEmpty(boolean errorOnSrcEmpty) {
this.errorOnSrcEmpty = errorOnSrcEmpty;
}

public boolean isErrorOnSrcEmpty() {
return errorOnSrcEmpty;
}
}