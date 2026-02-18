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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.cibseven.bpm.engine.impl.identity.IdentityProviderException;
import org.cibseven.bpm.identity.impl.scim.util.ScimPluginLogger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for SCIM API operations.
 */
public class ScimClient {

  protected final ScimConfiguration configuration;
  protected final ObjectMapper objectMapper;
  protected CloseableHttpClient httpClient;
  protected String cachedOAuth2Token;
  protected long tokenExpiryTime;  
  protected enum HttpMethod {GET, POST, PUT, PATCH, DEL};

  public ScimClient(ScimConfiguration configuration) {
    this.configuration = configuration;
    this.objectMapper = new ObjectMapper();
    checkConfiguration();
    initializeHttpClient();
  }

  protected void checkConfiguration() {
    if (configuration == null) {
      throw new IdentityProviderException("Failed to check SCIM configuration: configuration is empty.");
    }   
    if (configuration.getServerUrl() == null || configuration.getServerUrl().isEmpty()) {
      throw new IdentityProviderException("Failed to check SCIM configuration: serverUrl is not set.");
    }
  }

  @SuppressWarnings("deprecation")
  protected void initializeHttpClient() {
    try {
      PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder = PoolingHttpClientConnectionManagerBuilder.create();
      if (configuration.isAcceptUntrustedCertificates()) {
        try {
            SSLContext sslContext = createTrustAllSSLContext();
            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                sslContext, NoopHostnameVerifier.INSTANCE);
            connectionManagerBuilder.setSSLSocketFactory(sslSocketFactory);
        }
        catch (KeyManagementException | NoSuchAlgorithmException e) {
          throw new Exception("Failed to initialize trust-all ssl context", e);
        }
      }
      
      ConnectionConfig connectionConfig = ConnectionConfig.custom()
        .setConnectTimeout(configuration.getConnectionTimeout(), TimeUnit.MILLISECONDS)
        .setSocketTimeout(configuration.getSocketTimeout(), TimeUnit.MILLISECONDS)
        .build();
      
      PoolingHttpClientConnectionManager connectionManager = connectionManagerBuilder.build();
      connectionManager.setDefaultConnectionConfig(connectionConfig);
      connectionManager.setMaxTotal(configuration.getMaxConnections());
      connectionManager.setDefaultMaxPerRoute(configuration.getMaxConnections());
      httpClient = HttpClients.custom()
          .setConnectionManager(connectionManager)
          .build();
    } catch (Exception e) {
      throw new IdentityProviderException("Failed to initialize SCIM HTTP client", e);
    }
  }

  protected SSLContext createTrustAllSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
    TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
          public X509Certificate[] getAcceptedIssuers() {
            return null;
          }

          public void checkClientTrusted(X509Certificate[] certs, String authType) {
          }

          public void checkServerTrusted(X509Certificate[] certs, String authType) {
          }
        }
    };

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
    return sslContext;
  }

  /**
   * Search for users using SCIM filter.
   */
  public JsonNode searchUsers(String filter, int startIndex, int count, String sorting) {
    StringBuilder url = new StringBuilder(configuration.getServerUrl());
    url.append(configuration.getUsersEndpoint());
    
    boolean hasParams = false;
    if (filter != null && !filter.isEmpty()) {
      url.append("?filter=").append(encodeUrlParameter(filter));
      hasParams = true;
    }
    
    url.append(hasParams ? "&" : "?").append("startIndex=").append(startIndex);
    url.append("&count=").append(count);
    
    // sorting, contains sortby and orderby
    if (sorting != null) {
      url.append("&").append(sorting);
    }

    ScimPluginLogger.INSTANCE.scimFilterQuery(filter);
    return executeGet(url.toString());
  }

  /**
   * Search for groups using SCIM filter.
   */
  public JsonNode searchGroups(String filter, int startIndex, int count, String sorting) {
    StringBuilder url = new StringBuilder(configuration.getServerUrl());
    url.append(configuration.getGroupsEndpoint());
    
    boolean hasParams = false;
    if (filter != null && !filter.isEmpty()) {
      url.append("?filter=").append(encodeUrlParameter(filter));
      hasParams = true;
    }
    
    url.append(hasParams ? "&" : "?").append("startIndex=").append(startIndex);
    url.append("&count=").append(count);
    
   // sorting, contains sortby and orderby
    if (sorting != null) {
      url.append("&").append(sorting);
    }

    ScimPluginLogger.INSTANCE.scimFilterQuery(filter);
    return executeGet(url.toString());
  }

  /**
   * Get a specific user by scim ID.
   */
  public JsonNode getUserByScimId(String scimId) {
    String url = configuration.getServerUrl() + configuration.getUsersEndpoint() + "/" + encodeUrlParameter(scimId);
    return executeGet(url);
  }
  
  /**
   * Patch a specific user by scim ID.
   */
  public JsonNode patchUserByScimId(String scimId, JsonNode patchBody) {
    String url = configuration.getServerUrl() + configuration.getUsersEndpoint() + "/" + encodeUrlParameter(scimId);
    return executePatch(url, patchBody);
  }
   
  /**
   * Delete a specific user by scim ID.
   */
  public JsonNode deleteUserByScimId(String scimId) {
    String url = configuration.getServerUrl() + configuration.getUsersEndpoint() + "/" + encodeUrlParameter(scimId);
    return executeDel(url);
  }

  /**
   * Get a specific group by scim ID.
   */
  public JsonNode getGroupByScimId(String scimId) {
    String url = configuration.getServerUrl() + configuration.getGroupsEndpoint() + "/" + encodeUrlParameter(scimId);
    return executeGet(url);
  }
  
  /**
   * Patch a specific group by scim ID.
   */
  public JsonNode patchGroupByScimId(String scimId, JsonNode patchBody) {
    String url = configuration.getServerUrl() + configuration.getGroupsEndpoint() + "/" + encodeUrlParameter(scimId);
    return executePatch(url, patchBody);
  }
  
  /**
   * Delete a specific group by scim ID.
   */
  public JsonNode deleteGroupByScimId(String scimId) {
    String url = configuration.getServerUrl() + configuration.getGroupsEndpoint() + "/" + encodeUrlParameter(scimId);
    return executeDel(url);
  }

  protected JsonNode executeGet(String url) {
    return executeHttpRequest(HttpMethod.GET, url, null, false);
  }
  
  protected JsonNode executePost(String url, JsonNode postBody) {
    return executeHttpRequest(HttpMethod.POST, url, postBody, false);
  }
  
  protected JsonNode executeDel(String url) {
    return executeHttpRequest(HttpMethod.DEL, url, null, false);
  }
  
  protected JsonNode executePatch(String url, JsonNode patchBody) {
    return executeHttpRequest(HttpMethod.PATCH, url, patchBody, false);
  }
  
  @SuppressWarnings("deprecation")
  protected JsonNode executeHttpRequest(HttpMethod method, String url, JsonNode body, boolean isRetry) {
    HttpUriRequestBase request = 
        method == HttpMethod.GET ? new HttpGet(url) :
        method == HttpMethod.POST ? new HttpPost(url) : 
        method == HttpMethod.PUT ? new HttpPut(url) :
        method == HttpMethod.PATCH ? new HttpPatch(url) :
        method == HttpMethod.DEL ? new HttpDelete(url) : null;
       
    addAuthenticationHeader(request);
    addCustomHeaders(request);

    if (body != null) {
      request.setEntity(new StringEntity(
          body.toString(),
          ContentType.create("application/scim+json", StandardCharsets.UTF_8)
      ));
    }

    // System.out.println(">>>>>>> ScimClient " + method.toString() + ": " + url);
    try (CloseableHttpResponse response = httpClient.execute(request)) {
      int statusCode = response.getCode();
      String responseBody = response.getEntity() != null ? 
          EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";

      // System.out.println("<<<<<<< ScimClient " + method.toString() + " status code: " + statusCode);

      if (statusCode == 200 || statusCode == 201 || statusCode == 204) {
        return objectMapper.readTree(responseBody);
      } else if (statusCode == 401 && isOAuth2Authentication() && !isRetry) {
        // Try to refresh OAuth2 token and retry once
        refreshOAuth2Token();
        return executeHttpRequest(method, url, body, true);
      } else {
        ScimPluginLogger.INSTANCE.scimRequestError(statusCode, responseBody);
        throw new IdentityProviderException("SCIM request failed with status: " + statusCode);
      }
    } catch (IOException | ParseException e) {
      ScimPluginLogger.INSTANCE.httpClientException(method.toString() + " " + url, e);
      // System.out.println("<<<<<<< ScimClient " + method.toString() + " error: " + e.toString());
      throw new IdentityProviderException("SCIM HTTP request failed", e);
    }
  }

  protected void addAuthenticationHeader(HttpUriRequestBase request) {
    String authType = configuration.getAuthenticationType().toLowerCase();
    
    switch (authType) {
      case "bearer":
        if (configuration.getBearerToken() != null) {
          request.setHeader("Authorization", "Bearer " + configuration.getBearerToken());
        }
        break;
      case "basic":
        if (configuration.getUsername() != null && configuration.getPassword() != null) {
          String auth = configuration.getUsername() + ":" + configuration.getPassword();
          String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
          request.setHeader("Authorization", "Basic " + encodedAuth);
        }
        break;
      case "oauth2":
        ensureOAuth2Token();
        if (cachedOAuth2Token != null) {
          request.setHeader("Authorization", "Bearer " + cachedOAuth2Token);
        }
        break;
    }
  }

  protected void addCustomHeaders(HttpUriRequestBase request) {
    request.setHeader("Accept", "application/scim+json");
    request.setHeader("Content-Type", "application/scim+json");
    
    for (Map.Entry<String, String> header : configuration.getCustomHeaders().entrySet()) {
      request.setHeader(header.getKey(), header.getValue());
    }
  }

  protected boolean isOAuth2Authentication() {
    return "oauth2".equalsIgnoreCase(configuration.getAuthenticationType());
  }

  protected void ensureOAuth2Token() {
    if (cachedOAuth2Token == null || System.currentTimeMillis() >= tokenExpiryTime) {
      refreshOAuth2Token();
    }
  }

  @SuppressWarnings("deprecation")
  protected void refreshOAuth2Token() {
    if (configuration.getOauth2TokenUrl() == null) {
      throw new IdentityProviderException("OAuth2 token URL not configured");
    }

    ScimPluginLogger.INSTANCE.oauth2TokenRefresh();

    HttpPost request = new HttpPost(configuration.getOauth2TokenUrl());
    request.setHeader("Content-Type", "application/x-www-form-urlencoded");

    StringBuilder body = new StringBuilder();
    body.append("grant_type=client_credentials");
    body.append("&client_id=").append(encodeUrlParameter(configuration.getOauth2ClientId()));
    body.append("&client_secret=").append(encodeUrlParameter(configuration.getOauth2ClientSecret()));
    if (configuration.getOauth2Scope() != null) {
      body.append("&scope=").append(encodeUrlParameter(configuration.getOauth2Scope()));
    }

    request.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));

    try (CloseableHttpResponse response = httpClient.execute(request)) {
      int statusCode = response.getCode();
      String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

      if (statusCode == 200) {
        JsonNode tokenResponse = objectMapper.readTree(responseBody);
        cachedOAuth2Token = tokenResponse.get("access_token").asText();
        int expiresIn = tokenResponse.has("expires_in") ? tokenResponse.get("expires_in").asInt() : 3600;
        tokenExpiryTime = System.currentTimeMillis() + ((expiresIn - 60) * 1000L); // Refresh 1 minute early
      } else {
        ScimPluginLogger.INSTANCE.authenticationFailure("OAuth2 token request failed with status: " + statusCode);
        throw new IdentityProviderException("OAuth2 token request failed");
      }
    } catch (IOException | ParseException e) {
      ScimPluginLogger.INSTANCE.httpClientException("OAuth2 token refresh", e);
      throw new IdentityProviderException("OAuth2 token refresh failed", e);
    }
  }

  protected String encodeUrlParameter(String param) {
    if (param == null) {
      return "";
    }
    return URLEncoder.encode(param, StandardCharsets.UTF_8);
  }

  public void close() {
    if (httpClient != null) {
      try {
        httpClient.close();
      } catch (IOException e) {
        ScimPluginLogger.INSTANCE.httpClientException("close", e);
      }
    }
  }
}
