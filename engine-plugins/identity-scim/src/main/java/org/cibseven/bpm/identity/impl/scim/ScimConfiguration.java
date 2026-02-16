/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.identity.impl.scim;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for SCIM Identity Provider.
 */
public class ScimConfiguration {

  // SCIM server settings
  protected String serverUrl;
  protected String scimVersion = "2.0";
  
  // Authentication settings
  protected String authenticationType = "bearer"; // bearer, basic, oauth2
  protected String bearerToken;
  protected String username;
  protected String password;
  protected String oauth2TokenUrl;
  protected String oauth2ClientId;
  protected String oauth2ClientSecret;
  protected String oauth2Scope;
  protected boolean scimAuthenticationEnabled = false;
  
  // Endpoint configuration
  protected String usersEndpoint = "/Users";
  protected String groupsEndpoint = "/Groups";
  
  // User attribute mapping: our ScimUserEntity attributes to real scim attributes
  protected final String userScimIdAttribute = "id";
  protected String userIdAttribute = "userName";
  protected String userFirstnameAttribute = "name.givenName";
  protected String userLastnameAttribute = "name.familyName";
  protected String userPasswordAttribute = "password";
  protected String userEmailAttribute = "emails[type eq \"work\"].value";

  // Group attribute mapping
  protected final String groupScimIdAttribute = "id";
  protected String groupIdAttribute = "externalId";
  protected String groupNameAttribute = "displayName";
  protected String groupMembersAttribute = "members";
  
  // base filters for users and groups
  protected String userBaseFilter = "";
  protected String groupBaseFilter = "";
  
  // Connection settings
  protected int connectionTimeout = 30000; // milliseconds
  protected int socketTimeout = 30000; // milliseconds
  protected int maxConnections = 100;
  
  // SSL/TLS settings
  protected boolean useSsl = true;
  protected boolean acceptUntrustedCertificates = false;
  
  // Authorization settings
  protected boolean authorizationCheckEnabled = true;
  
  // Pagination
  protected Integer pageSize = 100;
  
  // Read only session
  protected boolean allowModifications = false;
  
  // Additional custom headers
  protected Map<String, String> customHeaders = new HashMap<>();

  // Getters and Setters

  public String getServerUrl() {
    return serverUrl;
  }

  public void setServerUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }

  public String getScimVersion() {
    return scimVersion;
  }

  public void setScimVersion(String scimVersion) {
    this.scimVersion = scimVersion;
  }

  public String getAuthenticationType() {
    return authenticationType;
  }

  public void setAuthenticationType(String authenticationType) {
    this.authenticationType = authenticationType;
  }

  public String getBearerToken() {
    return bearerToken;
  }

  public void setBearerToken(String bearerToken) {
    this.bearerToken = bearerToken;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getOauth2TokenUrl() {
    return oauth2TokenUrl;
  }

  public void setOauth2TokenUrl(String oauth2TokenUrl) {
    this.oauth2TokenUrl = oauth2TokenUrl;
  }

  public String getOauth2ClientId() {
    return oauth2ClientId;
  }

  public void setOauth2ClientId(String oauth2ClientId) {
    this.oauth2ClientId = oauth2ClientId;
  }

  public String getOauth2ClientSecret() {
    return oauth2ClientSecret;
  }

  public void setOauth2ClientSecret(String oauth2ClientSecret) {
    this.oauth2ClientSecret = oauth2ClientSecret;
  }

  public String getOauth2Scope() {
    return oauth2Scope;
  }

  public void setOauth2Scope(String oauth2Scope) {
    this.oauth2Scope = oauth2Scope;
  }
 
  public boolean getScimAuthenticationEnabled() {
    return scimAuthenticationEnabled;
  }

  public void setScimAuthenticationEnabled(boolean scimAuthenticationEnabled) {
   this.scimAuthenticationEnabled = scimAuthenticationEnabled;
  }

  public String getUsersEndpoint() {
    return usersEndpoint;
  }

  public void setUsersEndpoint(String usersEndpoint) {
    this.usersEndpoint = usersEndpoint.replaceAll ("/+$", "");
  }

  public String getGroupsEndpoint() {
    return groupsEndpoint;
  }

  public void setGroupsEndpoint(String groupsEndpoint) {
    this.groupsEndpoint = groupsEndpoint.replaceAll ("/+$", "");
  }

  public String getUserScimIdAttribute() {
    return userScimIdAttribute;
  }

  public String getUserIdAttribute() {
    return userIdAttribute;
  }

  public void setUserIdAttribute(String userIdAttribute) {
    this.userIdAttribute = userIdAttribute;
  }
  
  public String getUserFirstnameAttribute() {
    return userFirstnameAttribute;
  }

  public void setUserFirstnameAttribute(String userFirstnameAttribute) {
    this.userFirstnameAttribute = userFirstnameAttribute;
  }

  public String getUserLastnameAttribute() {
    return userLastnameAttribute;
  }

  public void setUserLastnameAttribute(String userLastnameAttribute) {
    this.userLastnameAttribute = userLastnameAttribute;
  }

  public String getUserEmailAttribute() {
    return userEmailAttribute;
  }

  public void setUserEmailAttribute(String userEmailAttribute) {
    this.userEmailAttribute = userEmailAttribute;
  }

  public String getGroupScimIdAttribute() {
    return groupScimIdAttribute;
  }
  
  public String getGroupIdAttribute() {
    return groupIdAttribute;
  }

  public void setGroupIdAttribute(String groupIdAttribute) {
    this.groupIdAttribute = groupIdAttribute;
  }

  public String getGroupNameAttribute() {
    return groupNameAttribute;
  }

  public void setGroupNameAttribute(String groupNameAttribute) {
    this.groupNameAttribute = groupNameAttribute;
  }

  public String getGroupMembersAttribute() {
    return groupMembersAttribute;
  }

  public void setGroupMembersAttribute(String groupMembersAttribute) {
    this.groupMembersAttribute = groupMembersAttribute;
  }

  public int getConnectionTimeout() {
    return connectionTimeout;
  }

  public void setConnectionTimeout(int connectionTimeout) {
    this.connectionTimeout = connectionTimeout;
  }

  public int getSocketTimeout() {
    return socketTimeout;
  }

  public void setSocketTimeout(int socketTimeout) {
    this.socketTimeout = socketTimeout;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public void setMaxConnections(int maxConnections) {
    this.maxConnections = maxConnections;
  }

  public boolean isUseSsl() {
    return useSsl;
  }

  public void setUseSsl(boolean useSsl) {
    this.useSsl = useSsl;
  }

  public boolean isAcceptUntrustedCertificates() {
    return acceptUntrustedCertificates;
  }

  public void setAcceptUntrustedCertificates(boolean acceptUntrustedCertificates) {
    this.acceptUntrustedCertificates = acceptUntrustedCertificates;
  }

  public boolean isAuthorizationCheckEnabled() {
    return authorizationCheckEnabled;
  }

  public void setAuthorizationCheckEnabled(boolean authorizationCheckEnabled) {
    this.authorizationCheckEnabled = authorizationCheckEnabled;
  }
  
  public boolean getAllowModifications() {
    return allowModifications;
  }

  public void setAllowModifications(boolean allowModifications) {
    this.allowModifications = allowModifications;
  }

  public Integer getPageSize() {
    return pageSize;
  }

  public void setPageSize(Integer pageSize) {
    this.pageSize = pageSize;
  }

  public Map<String, String> getCustomHeaders() {
    return customHeaders;
  }

  public void setCustomHeaders(Map<String, String> customHeaders) {
    this.customHeaders = customHeaders;
  }

  protected String getGroupBaseFilter() {
    return groupBaseFilter;
  }

  protected void setGroupBaseFilter(String groupBaseFilter) {
    this.groupBaseFilter = groupBaseFilter;
  }

  protected String getUserBaseFilter() {
    return userBaseFilter;
  }

  protected void setUserBaseFilter(String userBaseFilter) {
    this.userBaseFilter = userBaseFilter;
  }
}
