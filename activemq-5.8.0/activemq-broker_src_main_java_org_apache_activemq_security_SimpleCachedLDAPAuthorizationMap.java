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
package org.apache.activemq.security;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.filter.DestinationMapEntry;
import org.apache.activemq.jaas.GroupPrincipal;
import org.apache.activemq.jaas.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.event.*;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.*;

/**
*/
public class SimpleCachedLDAPAuthorizationMap extends DefaultAuthorizationMap {

private static final Logger LOG = LoggerFactory.getLogger(SimpleCachedLDAPAuthorizationMap.class);

// Configuration Options
private String initialContextFactory = "com.sun.jndi.ldap.LdapCtxFactory";
private String connectionURL = "ldap://localhost:1024";
private String connectionUsername = "uid=admin,ou=system";
private String connectionPassword = "secret";
private String connectionProtocol = "s";
private String authentication = "simple";


private int queuePrefixLength = 4;
private int topicPrefixLength = 4;
private int tempPrefixLength = 4;

private String queueSearchBase = "ou=Queue,ou=Destination,ou=ActiveMQ,ou=system";
private String topicSearchBase = "ou=Topic,ou=Destination,ou=ActiveMQ,ou=system";
private String tempSearchBase = "ou=Temp,ou=Destination,ou=ActiveMQ,ou=system";


private String permissionGroupMemberAttribute = "member";

private String adminPermissionGroupSearchFilter = "(cn=Admin)";
private String readPermissionGroupSearchFilter = "(cn=Read)";
private String writePermissionGroupSearchFilter = "(cn=Write)";

private boolean legacyGroupMapping = true;
private String groupObjectClass = "groupOfNames";
private String userObjectClass = "person";
private String groupNameAttribute = "cn";
private String userNameAttribute = "uid";


private int refreshInterval = -1;
private boolean refreshDisabled = false;

// Internal State
private long lastUpdated;

private static String ANY_DESCENDANT = "\\$";

protected DirContext context;
private EventDirContext eventContext;

protected HashMap<ActiveMQDestination, AuthorizationEntry> entries =
new HashMap<ActiveMQDestination, AuthorizationEntry>();

protected DirContext createContext() throws NamingException {
Hashtable<String, String> env = new Hashtable<String, String>();
env.put(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
if (connectionUsername != null || !"".equals(connectionUsername)) {
env.put(Context.SECURITY_PRINCIPAL, connectionUsername);
}
if (connectionPassword != null || !"".equals(connectionPassword)) {
env.put(Context.SECURITY_CREDENTIALS, connectionPassword);
}
env.put(Context.SECURITY_PROTOCOL, connectionProtocol);
env.put(Context.PROVIDER_URL, connectionURL);
env.put(Context.SECURITY_AUTHENTICATION, authentication);
return new InitialDirContext(env);
}

protected boolean isContextAlive() {
boolean alive = false;
if (context != null) {
try {
context.getAttributes("");
alive = true;
} catch (Exception e) {}
}
return alive;
}

/**
* Returns the existing open context or creates a new one and registers listeners for
* push notifications if such an update style is enabled.  This implementation should not
* be invoked concurrently.
*
* @return the current context
*
* @throws NamingException if there is an error setting things up
*/
protected DirContext open() throws NamingException {
if (isContextAlive()) {
return context;
}

try {
context = createContext();
if (refreshInterval == -1 && !refreshDisabled) {
eventContext = ((EventDirContext)context.lookup(""));

final SearchControls constraints = new SearchControls();
constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);

// Listeners for Queue policy //

// Listeners for each type of permission
for (PermissionType permissionType : PermissionType.values()) {
eventContext.addNamingListener(queueSearchBase, getFilterForPermissionType(permissionType), constraints,
this.new CachedLDAPAuthorizationMapNamespaceChangeListener(DestinationType.QUEUE, permissionType));
}
// Listener for changes to the destination pattern entry itself and not a permission entry.
eventContext.addNamingListener(queueSearchBase, "cn=*", new SearchControls(),
this.new CachedLDAPAuthorizationMapNamespaceChangeListener(DestinationType.QUEUE, null));

// Listeners for Topic policy //

// Listeners for each type of permission
for (PermissionType permissionType : PermissionType.values()) {
eventContext.addNamingListener(topicSearchBase, getFilterForPermissionType(permissionType), constraints,
this.new CachedLDAPAuthorizationMapNamespaceChangeListener(DestinationType.TOPIC, permissionType));
}
// Listener for changes to the destination pattern entry itself and not a permission entry.
eventContext.addNamingListener(topicSearchBase, "cn=*", new SearchControls(),
this.new CachedLDAPAuthorizationMapNamespaceChangeListener(DestinationType.TOPIC, null));

// Listeners for Temp policy //

// Listeners for each type of permission
for (PermissionType permissionType : PermissionType.values()) {
eventContext.addNamingListener(tempSearchBase, getFilterForPermissionType(permissionType), constraints,
this.new CachedLDAPAuthorizationMapNamespaceChangeListener(DestinationType.TEMP, permissionType));
}

}
} catch (NamingException e) {
context = null;
throw e;
}

return context;
}

/**
* Queries the directory and initializes the policy based on the data in the directory.
* This implementation should not be invoked concurrently.
*
* @throws Exception if there is an unrecoverable error processing the directory contents
*/
@SuppressWarnings("rawtypes")
protected void query() throws Exception {
DirContext currentContext = open();

final SearchControls constraints = new SearchControls();
constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);

for (PermissionType permissionType : PermissionType.values()) {
try {
processQueryResults(
currentContext.search(queueSearchBase, getFilterForPermissionType(permissionType), constraints),
DestinationType.QUEUE, permissionType);
} catch (Exception e) {
LOG.error("Policy not applied!.  Error processing policy under '" + queueSearchBase + "' with filter '"
+ getFilterForPermissionType(permissionType) + "'", e);
}
}

for (PermissionType permissionType : PermissionType.values()) {
try {
processQueryResults(
currentContext.search(topicSearchBase, getFilterForPermissionType(permissionType), constraints),
DestinationType.TOPIC, permissionType);
} catch (Exception e) {
LOG.error("Policy not applied!.  Error processing policy under '" + topicSearchBase + "' with filter '"
+ getFilterForPermissionType(permissionType) + "'", e);
}
}

for (PermissionType permissionType : PermissionType.values()) {
try {
processQueryResults(
currentContext.search(tempSearchBase, getFilterForPermissionType(permissionType), constraints),
DestinationType.TEMP, permissionType);
} catch (Exception e) {
LOG.error("Policy not applied!.  Error processing policy under '" + tempSearchBase + "' with filter '"
+ getFilterForPermissionType(permissionType) + "'", e);
}
}

setEntries(new ArrayList<DestinationMapEntry>(entries.values()));
updated();
}

/**
* Processes results from a directory query in the context of a given destination type and permission type.
* This implementation should not be invoked concurrently.
*
* @param results the results to process
* @param destinationType the type of the destination for which the directory results apply
* @param permissionType the type of the permission for which the directory results apply
*
* @throws Exception if there is an error processing the results
*/
protected void processQueryResults(NamingEnumeration<SearchResult> results,
DestinationType destinationType, PermissionType permissionType) throws Exception {

while (results.hasMore()) {
SearchResult result = results.next();
AuthorizationEntry entry = null;

try {
entry = getEntry(new LdapName(result.getNameInNamespace()), destinationType);
} catch (Exception e) {
LOG.error("Policy not applied!  Error parsing authorization policy entry under "
+ result.getNameInNamespace(), e);
continue;
}

applyACL(entry, result, permissionType);
}
}

/**
* Marks the time at which the authorization state was last refreshed.  Relevant for synchronous policy updates.
* This implementation should not be invoked concurrently.
*/
protected void updated() {
lastUpdated = System.currentTimeMillis();
}

/**
* Retrieves or creates the {@link AuthorizationEntry} that corresponds to
* the DN in {@code dn}.  This implementation should not be invoked concurrently.
*
* @param dn
*            the DN representing the policy entry in the directory
* @param destinationType the type of the destination to get/create the entry for
*
* @return the corresponding authorization entry for the DN
*
* @throws IllegalArgumentException
*             if destination type is not one of {@link DestinationType#QUEUE}, {@link DestinationType#TOPIC},
*             {@link DestinationType#TEMP} or if the policy entry DN is malformed
*/
protected AuthorizationEntry getEntry(LdapName dn, DestinationType destinationType) {

AuthorizationEntry entry = null;


switch (destinationType) {
case TEMP:
// handle temp entry
if (dn.size() != getPrefixLengthForDestinationType(destinationType) + 1) {
// handle unknown entry
throw new IllegalArgumentException("Malformed policy structure for a temporary destination "
+ "policy entry.  The permission group entries should be immediately below the "
+ "temporary policy base DN.");
}
entry = getTempDestinationAuthorizationEntry();
if (entry == null) {
entry = new TempDestinationAuthorizationEntry();
setTempDestinationAuthorizationEntry((TempDestinationAuthorizationEntry) entry);
}

break;

case QUEUE:
case TOPIC:
// handle regular destinations
if (dn.size() != getPrefixLengthForDestinationType(destinationType) + 2) {
throw new IllegalArgumentException("Malformed policy structure for a queue or topic destination "
+ "policy entry.  The destination pattern and permission group entries should be "
+ "nested below the queue or topic policy base DN.");
}

ActiveMQDestination dest = formatDestination(dn, destinationType);

if (dest != null) {
entry = entries.get(dest);
if (entry == null) {
entry = new AuthorizationEntry();
entry.setDestination(dest);
entries.put(dest, entry);
}
}

break;
default:
// handle unknown entry
throw new IllegalArgumentException("Unknown destination type " + destinationType);
}

return entry;
}

/**
* Applies the policy from the directory to the given entry within the context of the provided
* permission type.
*
* @param entry the policy entry to apply the policy to
* @param result the results from the directory to apply to the policy entry
* @param permissionType the permission type of the data in the directory
*
* @throws NamingException if there is an error applying the ACL
*/
protected void applyACL(AuthorizationEntry entry, SearchResult result,
PermissionType permissionType) throws NamingException {

// Find members
Attribute memberAttribute = result.getAttributes().get(permissionGroupMemberAttribute);
NamingEnumeration<?> memberAttributeEnum = memberAttribute.getAll();

HashSet<Object> members = new HashSet<Object>();

while (memberAttributeEnum.hasMoreElements()) {
String memberDn = (String) memberAttributeEnum.nextElement();
boolean group = false;
boolean user = false;
String principalName = null;

if (!legacyGroupMapping) {
// Lookup of member to determine principal type (group or user) and name.
Attributes memberAttributes;
try {
memberAttributes = context.getAttributes(memberDn,
new String[] {"objectClass", groupNameAttribute, userNameAttribute});
} catch (NamingException e) {
LOG.error(
"Policy not applied! Unknown member " + memberDn
+ " in policy entry "
+ result.getNameInNamespace(), e);
continue;
}

Attribute memberEntryObjectClassAttribute = memberAttributes.get("objectClass");
NamingEnumeration<?> memberEntryObjectClassAttributeEnum = memberEntryObjectClassAttribute.getAll();

while (memberEntryObjectClassAttributeEnum.hasMoreElements()) {
String objectClass = (String) memberEntryObjectClassAttributeEnum.nextElement();

if (objectClass.equalsIgnoreCase(groupObjectClass)) {
group = true;
Attribute name = memberAttributes.get(groupNameAttribute);
if (name == null) {
LOG.error("Policy not applied! Group "
+ memberDn
+ "does not have name attribute "
+ groupNameAttribute + " under entry " + result.getNameInNamespace());
break;
}

principalName = (String) name.get();
}

if (objectClass.equalsIgnoreCase(userObjectClass)) {
user = true;
Attribute name = memberAttributes.get(userNameAttribute);
if (name == null) {
LOG.error("Policy not applied! User "
+ memberDn + " does not have name attribute "
+ userNameAttribute + " under entry " + result.getNameInNamespace());
break;
}

principalName = (String) name.get();
}
}

} else {
group = true;
principalName = memberDn.replaceAll("(cn|CN)=", "");
}

if ((!group && !user) || (group && user)) {
LOG.error("Policy not applied! Can't determine type of member "
+ memberDn + " under entry " + result.getNameInNamespace());
} else if (principalName != null){
if (group && !user) {
members.add(new GroupPrincipal(principalName));
} else if (!group && user) {
members.add(new UserPrincipal(principalName));
}
}
}

try {
applyAcl(entry, permissionType, members);
} catch (Exception e) {
LOG.error(
"Policy not applied! Error adding principals to ACL under "
+ result.getNameInNamespace(), e);
}
}

/**
* Applies policy to the entry given the actual principals that will be applied to the policy entry.
*
* @param entry the policy entry to which the policy should be applied
* @param permissionType the type of the permission that the policy will be applied to
* @param acls the principals that represent the actual policy
*
* @throw IllegalArgumentException if {@code permissionType} is unsupported
*/
protected void applyAcl(AuthorizationEntry entry, PermissionType permissionType, Set<Object> acls) {

switch (permissionType) {
case READ:
entry.setReadACLs(acls);
break;
case WRITE:
entry.setWriteACLs(acls);
break;
case ADMIN:
entry.setAdminACLs(acls);
break;
default:
throw new IllegalArgumentException("Unknown permission " + permissionType + ".");
}
}

/**
* Parses a DN into the equivalent {@link ActiveMQDestination}.  The default implementation
* expects a format of cn=<PERMISSION_NAME>,ou=<DESTINATION_PATTERN>,.... or
* ou=<DESTINATION_PATTERN>,.... for permission and destination entries, respectively.
* For example {@code cn=admin,ou=$,ou=...} or {@code ou=$,ou=...}.
*
* @param dn the DN to parse
* @param destinationType the type of the destination that we are parsing
*
* @return the destination that the DN represents
*
* @throws IllegalArgumentException if {@code destinationType} is {@link DestinationType#TEMP} or
* if the format of {@code dn} is incorrect for for a topic or queue
*
* @see #formatDestination(Rdn, DestinationType)
*/
protected ActiveMQDestination formatDestination(LdapName dn, DestinationType destinationType) {
ActiveMQDestination destination = null;

switch (destinationType) {
case QUEUE:
case TOPIC:
// There exists a need to deal with both names representing a permission or simply a
// destination.  As such, we need to determine the proper RDN to work with based
// on the destination type and the DN size.
if (dn.size() == (getPrefixLengthForDestinationType(destinationType) + 2)) {
destination = formatDestination(dn.getRdn(dn.size() - 2), destinationType);
} else if (dn.size() == (getPrefixLengthForDestinationType(destinationType) + 1)){
destination = formatDestination(dn.getRdn(dn.size() - 1), destinationType);
} else {
throw new IllegalArgumentException(
"Malformed DN for representing a permission or destination entry.");
}
break;
default:
throw new IllegalArgumentException(
"Cannot format destination for destination type " + destinationType);
}

return destination;
}

/**
* Parses RDN values representing the destination name/pattern and
* destination type into the equivalent {@link ActiveMQDestination}.
*
* @param destinationName
*            the RDN representing the name or pattern for the destination
* @param destinationType
*            the type of the destination
*
* @return the destination that the RDN represent
*
* @throws IllegalArgumentException
*             if {@code destinationType} is not one of {@link DestinationType#TOPIC} or
*             {@link DestinationType#QUEUE}.
*
* @see #formatDestinationName(Rdn)
* @see #formatDestination(LdapName, DestinationType)
*/
protected ActiveMQDestination formatDestination(Rdn destinationName, DestinationType destinationType) {
ActiveMQDestination dest = null;

switch (destinationType) {
case QUEUE:
dest = new ActiveMQQueue(formatDestinationName(destinationName));
break;
case TOPIC:
dest = new ActiveMQTopic(formatDestinationName(destinationName));
break;
default:
throw new IllegalArgumentException("Unknown destination type: "
+ destinationType);
}

return dest;
}

/**
* Parses the RDN representing a destination name/pattern into the standard string representation
* of the name/pattern.  This implementation does not care about the type of the RDN such that the RDN could
* be a CN or OU.
*
* @param destinationName the RDN representing the name or pattern for the destination
*
* @see #formatDestination(Rdn, Rdn)
*/
protected String formatDestinationName(Rdn destinationName) {
return destinationName.getValue().toString().replaceAll(ANY_DESCENDANT, ">");
}

/**
* Transcribes an existing set into a new set. Used to make defensive copies
* for concurrent access.
*
* @param source
*            the source set or {@code null}
*
* @return a new set containing the same elements as {@code source} or
*         {@code null} if {@code source} is {@code null}
*/
protected <T> Set<T> transcribeSet(Set<T> source) {
if (source != null) {
return new HashSet<T>(source);
} else {
return null;
}
}

/**
* Returns the filter string for the given permission type.
*
* @throws IllegalArgumentException if {@code permissionType} is not supported
*
* @see #setAdminPermissionGroupSearchFilter(String)
* @see #setReadPermissionGroupSearchFilter(String)
* @see #setWritePermissionGroupSearchFilter(String)
*/
protected String getFilterForPermissionType(PermissionType permissionType) {
String filter = null;

switch (permissionType) {
case ADMIN:
filter = adminPermissionGroupSearchFilter;
break;
case READ:
filter = readPermissionGroupSearchFilter;
break;
case WRITE:
filter = writePermissionGroupSearchFilter;
break;
default:
throw new IllegalArgumentException("Unknown permission type " + permissionType);
}

return filter;
}

/**
* Returns the DN prefix size based on the given destination type.
*
* @throws IllegalArgumentException if {@code destinationType} is not supported
*
* @see #setQueueSearchBase(String)
* @see #setTopicSearchBase(String)
* @see #setTempSearchBase(String)
*/
protected int getPrefixLengthForDestinationType(DestinationType destinationType) {
int filter = 0;

switch (destinationType) {
case QUEUE:
filter = queuePrefixLength;
break;
case TOPIC:
filter = topicPrefixLength;
break;
case TEMP:
filter = tempPrefixLength;
break;
default:
throw new IllegalArgumentException("Unknown permission type " + destinationType);
}

return filter;
}

/**
* Performs a check for updates from the server in the event that synchronous updates are enabled
* and are the refresh interval has elapsed.
*/
protected void checkForUpdates() {
if (context == null || (!refreshDisabled && (refreshInterval != -1 && System.currentTimeMillis() >= lastUpdated + refreshInterval))) {
if (!isContextAlive()) {
try {
context = createContext();
} catch (NamingException ne) {
// LDAP is down, use already cached values
return;
}
}
reset();
setTempDestinationAuthorizationEntry(null);
entries.clear();

LOG.debug("Updating authorization map!");
try {
query();
} catch (Exception e) {
LOG.error("Error updating authorization map.  Partial policy "
+ "may be applied until the next successful update.", e);
}
}
}

// Authorization Map

/**
* Provides synchronous refresh capabilities if so configured before delegating to the super implementation,
* and otherwise simply delegates to the super implementation.
*/
@Override
protected synchronized Set<AuthorizationEntry> getAllEntries(ActiveMQDestination destination) {
checkForUpdates();
return super.getAllEntries(destination);
}

/**
* Provides synchronized and defensive access to the admin ACLs for temp destinations as the super
* implementation returns live copies of the ACLs and {@link AuthorizationEntry} is not
* setup for concurrent access.
*/
@Override
public synchronized Set<Object> getTempDestinationAdminACLs() {
checkForUpdates();
return transcribeSet(super.getTempDestinationAdminACLs());
}

/**
* Provides synchronized and defensive access to the read ACLs for temp destinations as the super
* implementation returns live copies of the ACLs and {@link AuthorizationEntry} is not
* setup for concurrent access.
*/
public synchronized Set<Object> getTempDestinationReadACLs() {
checkForUpdates();
return transcribeSet(super.getTempDestinationReadACLs());
}

/**
* Provides synchronized and defensive access to the write ACLs for temp destinations as the super
* implementation returns live copies of the ACLs and {@link AuthorizationEntry} is not
* setup for concurrent access.
*/
public synchronized Set<Object> getTempDestinationWriteACLs() {
checkForUpdates();
return transcribeSet(super.getTempDestinationWriteACLs());
}

/**
* Provides synchronized access to the admin ACLs for the destinations as
* {@link AuthorizationEntry} is not setup for concurrent access.
*/
public synchronized Set<Object> getAdminACLs(ActiveMQDestination destination) {
return super.getAdminACLs(destination);
}

/**
* Provides synchronized access to the read ACLs for the destinations as
* {@link AuthorizationEntry} is not setup for concurrent access.
*/
public synchronized Set<Object> getReadACLs(ActiveMQDestination destination) {
checkForUpdates();
return super.getReadACLs(destination);
}

/**
* Provides synchronized access to the write ACLs for the destinations as
* {@link AuthorizationEntry} is not setup for concurrent access.
*/
public synchronized Set<Object> getWriteACLs(ActiveMQDestination destination) {
checkForUpdates();
return super.getWriteACLs(destination);
}

/**
* Handler for new policy entries in the directory.
*
* @param namingEvent the new entry event that occurred
* @param destinationType the type of the destination to which the event applies
* @param permissionType the permission type to which the event applies
*/
public synchronized void objectAdded(NamingEvent namingEvent, DestinationType destinationType,
PermissionType permissionType) {
LOG.debug("Adding object: " + namingEvent.getNewBinding());
SearchResult result = (SearchResult) namingEvent.getNewBinding();

try {
LdapName name = new LdapName(result.getName());

AuthorizationEntry entry = getEntry(name, destinationType);

applyACL(entry, result, permissionType);
if (!(entry instanceof TempDestinationAuthorizationEntry)) {
put(entry.getDestination(), entry);
}

} catch (InvalidNameException e) {
LOG.error("Policy not applied!  Error parsing DN for addition of "
+ result.getName(), e);
} catch (Exception e) {
LOG.error("Policy not applied!  Error processing object addition for addition of "
+ result.getName(), e);
}
}

/**
* Handler for removed policy entries in the directory.
*
* @param namingEvent the removed entry event that occurred
* @param destinationType the type of the destination to which the event applies
* @param permissionType the permission type to which the event applies
*/
public synchronized void objectRemoved(NamingEvent namingEvent, DestinationType destinationType,
PermissionType permissionType) {
LOG.debug("Removing object: " + namingEvent.getOldBinding());
Binding result = namingEvent.getOldBinding();

try {
LdapName name = new LdapName(result.getName());

AuthorizationEntry entry = getEntry(name, destinationType);

applyAcl(entry, permissionType, new HashSet<Object>());
} catch (InvalidNameException e) {
LOG.error("Policy not applied!  Error parsing DN for object removal for removal of "
+ result.getName(), e);
} catch (Exception e) {
LOG.error("Policy not applied!  Error processing object removal for removal of "
+ result.getName(), e);
}
}

/**
* Handler for renamed policy entries in the directory.  This handler deals with the renaming
* of destination entries as well as permission entries.  If the permission type is not null, it is
* assumed that we are dealing with the renaming of a permission entry.  Otherwise, it is assumed
* that we are dealing with the renaming of a destination entry.
*
* @param namingEvent the renaming entry event that occurred
* @param destinationType the type of the destination to which the event applies
* @param permissionType the permission type to which the event applies
*/
public synchronized void objectRenamed(NamingEvent namingEvent, DestinationType destinationType,
PermissionType permissionType) {
Binding oldBinding = namingEvent.getOldBinding();
Binding newBinding = namingEvent.getNewBinding();
LOG.debug("Renaming object: " + oldBinding + " to " + newBinding);

try {
LdapName oldName = new LdapName(oldBinding.getName());
ActiveMQDestination oldDest = formatDestination(oldName, destinationType);

LdapName newName = new LdapName(newBinding.getName());
ActiveMQDestination newDest = formatDestination(newName, destinationType);

if (permissionType != null) {
// Handle the case where a permission entry is being renamed.
objectRemoved(namingEvent, destinationType, permissionType);

SearchControls controls = new SearchControls();
controls.setSearchScope(SearchControls.OBJECT_SCOPE);

boolean matchedToType = false;

for (PermissionType newPermissionType : PermissionType.values()) {
NamingEnumeration<SearchResult> results = context.search(
newName,
getFilterForPermissionType(newPermissionType), controls);

if (results.hasMore()) {
objectAdded(namingEvent, destinationType, newPermissionType);
matchedToType = true;
break;
}
}

if (!matchedToType) {
LOG.error("Policy not applied!  Error processing object rename for rename of "
+ oldBinding.getName() + " to " + newBinding.getName()
+ ".  Could not determine permission type of new object.");
}

} else {
// Handle the case where a destination entry is being renamed.
if (oldDest != null && newDest != null) {
AuthorizationEntry entry = entries.remove(oldDest);
if (entry != null) {
entry.setDestination(newDest);
put(newDest, entry);
remove(oldDest, entry);
entries.put(newDest, entry);
} else {
LOG.warn("No authorization entry for " + oldDest);
}
}
}
} catch (InvalidNameException e) {
LOG.error("Policy not applied!  Error parsing DN for object rename for rename of "
+ oldBinding.getName() + " to " + newBinding.getName(), e);
} catch (Exception e) {
LOG.error("Policy not applied!  Error processing object rename for rename of "
+ oldBinding.getName() + " to " + newBinding.getName(), e);
}
}

/**
* Handler for changed policy entries in the directory.
*
* @param namingEvent the changed entry event that occurred
* @param destinationType the type of the destination to which the event applies
* @param permissionType the permission type to which the event applies
*/
public synchronized void objectChanged(NamingEvent namingEvent,
DestinationType destinationType, PermissionType permissionType) {
LOG.debug("Changing object " + namingEvent.getOldBinding() + " to " + namingEvent.getNewBinding());
objectRemoved(namingEvent, destinationType, permissionType);
objectAdded(namingEvent, destinationType, permissionType);
}

/**
* Handler for exception events from the registry.
*
* @param namingExceptionEvent the exception event
*/
public void namingExceptionThrown(NamingExceptionEvent namingExceptionEvent) {
context = null;
LOG.error("Caught unexpected exception.", namingExceptionEvent.getException());
}

// Init / Destroy
public void afterPropertiesSet() throws Exception {
query();
}

public void destroy() throws Exception {
if (eventContext != null) {
eventContext.close();
eventContext = null;
}

if (context != null) {
context.close();
context = null;
}
}

// Getters and Setters

public String getConnectionURL() {
return connectionURL;
}

public void setConnectionURL(String connectionURL) {
this.connectionURL = connectionURL;
}

public String getConnectionUsername() {
return connectionUsername;
}

public void setConnectionUsername(String connectionUsername) {
this.connectionUsername = connectionUsername;
}

public String getConnectionPassword() {
return connectionPassword;
}

public void setConnectionPassword(String connectionPassword) {
this.connectionPassword = connectionPassword;
}

public String getConnectionProtocol() {
return connectionProtocol;
}

public void setConnectionProtocol(String connectionProtocol) {
this.connectionProtocol = connectionProtocol;
}

public String getAuthentication() {
return authentication;
}

public void setAuthentication(String authentication) {
this.authentication = authentication;
}

public String getQueueSearchBase() {
return queueSearchBase;
}

public void setQueueSearchBase(String queueSearchBase) {
try {
LdapName baseName = new LdapName(queueSearchBase);
queuePrefixLength = baseName.size();
this.queueSearchBase = queueSearchBase;
} catch (InvalidNameException e) {
throw new IllegalArgumentException("Invalid base DN value " + queueSearchBase, e);
}
}

public String getTopicSearchBase() {
return topicSearchBase;
}

public void setTopicSearchBase(String topicSearchBase) {
try {
LdapName baseName = new LdapName(topicSearchBase);
topicPrefixLength = baseName.size();
this.topicSearchBase = topicSearchBase;
} catch (InvalidNameException e) {
throw new IllegalArgumentException("Invalid base DN value " + topicSearchBase, e);
}
}

public String getTempSearchBase() {
return tempSearchBase;
}

public void setTempSearchBase(String tempSearchBase) {
try {
LdapName baseName = new LdapName(tempSearchBase);
tempPrefixLength = baseName.size();
this.tempSearchBase = tempSearchBase;
} catch (InvalidNameException e) {
throw new IllegalArgumentException("Invalid base DN value " + tempSearchBase, e);
}
}

public String getPermissionGroupMemberAttribute() {
return permissionGroupMemberAttribute;
}

public void setPermissionGroupMemberAttribute(
String permissionGroupMemberAttribute) {
this.permissionGroupMemberAttribute = permissionGroupMemberAttribute;
}

public String getAdminPermissionGroupSearchFilter() {
return adminPermissionGroupSearchFilter;
}

public void setAdminPermissionGroupSearchFilter(
String adminPermissionGroupSearchFilter) {
this.adminPermissionGroupSearchFilter = adminPermissionGroupSearchFilter;
}

public String getReadPermissionGroupSearchFilter() {
return readPermissionGroupSearchFilter;
}

public void setReadPermissionGroupSearchFilter(
String readPermissionGroupSearchFilter) {
this.readPermissionGroupSearchFilter = readPermissionGroupSearchFilter;
}

public String getWritePermissionGroupSearchFilter() {
return writePermissionGroupSearchFilter;
}

public void setWritePermissionGroupSearchFilter(
String writePermissionGroupSearchFilter) {
this.writePermissionGroupSearchFilter = writePermissionGroupSearchFilter;
}

public boolean isLegacyGroupMapping() {
return legacyGroupMapping;
}

public void setLegacyGroupMapping(boolean legacyGroupMapping) {
this.legacyGroupMapping = legacyGroupMapping;
}

public String getGroupObjectClass() {
return groupObjectClass;
}

public void setGroupObjectClass(String groupObjectClass) {
this.groupObjectClass = groupObjectClass;
}

public String getUserObjectClass() {
return userObjectClass;
}

public void setUserObjectClass(String userObjectClass) {
this.userObjectClass = userObjectClass;
}

public String getGroupNameAttribute() {
return groupNameAttribute;
}

public void setGroupNameAttribute(String groupNameAttribute) {
this.groupNameAttribute = groupNameAttribute;
}

public String getUserNameAttribute() {
return userNameAttribute;
}

public void setUserNameAttribute(String userNameAttribute) {
this.userNameAttribute = userNameAttribute;
}

public boolean isRefreshDisabled() {
return refreshDisabled;
}

public void setRefreshDisabled(boolean refreshDisabled) {
this.refreshDisabled = refreshDisabled;
}

public int getRefreshInterval() {
return refreshInterval;
}

public void setRefreshInterval(int refreshInterval) {
this.refreshInterval = refreshInterval;
}

protected static enum DestinationType {
QUEUE,
TOPIC,
TEMP;
}

protected static enum PermissionType {
READ,
WRITE,
ADMIN;
}

/**
* Listener implementation for directory changes that maps change events to
* destination types.
*/
protected class CachedLDAPAuthorizationMapNamespaceChangeListener implements
NamespaceChangeListener, ObjectChangeListener {

private final DestinationType destinationType;
private final PermissionType permissionType;

/**
* Creates a new listener.  If {@code permissionType} is {@code null}, add
* and remove events are ignored as they do not directly affect policy state.
* This configuration is used when listening for changes on entries that represent
* destination patterns and not for entries that represent permissions.
*
* @param destinationType the type of the destination being listened for
* @param permissionType the optional permission type being listened for
*/
public CachedLDAPAuthorizationMapNamespaceChangeListener(
DestinationType destinationType, PermissionType permissionType) {
this.destinationType = destinationType;
this.permissionType = permissionType;
}

@Override
public void namingExceptionThrown(NamingExceptionEvent evt) {
SimpleCachedLDAPAuthorizationMap.this.namingExceptionThrown(evt);
}

@Override
public void objectAdded(NamingEvent evt) {
// This test is a hack to work around the fact that Apache DS 2.0 seems to trigger notifications
// for the entire sub-tree even when one-level is the selected search scope.
if (permissionType != null) {
SimpleCachedLDAPAuthorizationMap.this.objectAdded(evt, destinationType, permissionType);
}
}

@Override
public void objectRemoved(NamingEvent evt) {
// This test is a hack to work around the fact that Apache DS 2.0 seems to trigger notifications
// for the entire sub-tree even when one-level is the selected search scope.
if (permissionType != null) {
SimpleCachedLDAPAuthorizationMap.this.objectRemoved(evt, destinationType, permissionType);
}
}

@Override
public void objectRenamed(NamingEvent evt) {
SimpleCachedLDAPAuthorizationMap.this.objectRenamed(evt, destinationType, permissionType);
}

@Override
public void objectChanged(NamingEvent evt) {
// This test is a hack to work around the fact that Apache DS 2.0 seems to trigger notifications
// for the entire sub-tree even when one-level is the selected search scope.
if (permissionType != null) {
SimpleCachedLDAPAuthorizationMap.this.objectChanged(evt, destinationType, permissionType);
}
}
}
}