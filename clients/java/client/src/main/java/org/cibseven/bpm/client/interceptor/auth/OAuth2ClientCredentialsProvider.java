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
package org.cibseven.bpm.client.interceptor.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.cibseven.bpm.client.exception.ExternalTaskClientException;
import org.cibseven.bpm.client.impl.ExternalTaskClientLogger;
import org.cibseven.bpm.client.interceptor.ClientRequestContext;
import org.cibseven.bpm.client.interceptor.ClientRequestInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.hc.core5.http.HttpHeaders.AUTHORIZATION;

/**
 * <p>Provides OAuth2 client credentials flow authentication by using the request interceptor api.</p>
 *
 * <p>Acquires a Bearer access token from the configured token endpoint and sets it as
 * the {@code Authorization: Bearer} header on every outgoing request. Tokens are cached
 * in memory and refreshed automatically before they expire.</p>
 *
 * <p>Two client authentication modes are supported:</p>
 * <ul>
 *   <li><b>Client secret</b> ({@code client_secret_post}): provide a {@code clientSecret}</li>
 *   <li><b>Client assertion JWT</b> ({@code private_key_jwt}): provide a {@link ClientAssertionProvider},
 *       e.g. {@link JjwtClientAssertionProvider} for RSA/EC key signing or
 *       {@link AzureWorkloadIdentityAssertionProvider} for Azure Workload Identity federated tokens</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ClientRequestInterceptor oauth2 = OAuth2ClientCredentialsProvider.builder()
 *   .tokenUri("https://idp.example.com/token")
 *   .clientId("my-client")
 *   .clientSecret("my-secret")
 *   .scope("engine-rest/.default")     // optional
 *   .build();
 *
 * ExternalTaskClient client = ExternalTaskClient.create()
 *   .baseUrl("http://localhost:8080/engine-rest")
 *   .addInterceptor(oauth2)
 *   .build();
 * }</pre>
 */
public class OAuth2ClientCredentialsProvider implements ClientRequestInterceptor, java.io.Closeable {

  protected static final ExternalTaskClientLogger LOG = ExternalTaskClientLogger.CLIENT_LOGGER;

  protected static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
  protected static final String ASSERTION_TYPE =
      "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

  protected final String tokenUri;
  protected final String clientId;
  protected final String clientSecret;
  protected final ClientAssertionProvider clientAssertionProvider;
  protected final String scope;
  protected final String audience;
  protected final String resource;
  protected final Map<String, String> additionalParameters;
  protected final Duration expiryBuffer;

  protected final CloseableHttpClient httpClient;
  protected final ObjectMapper objectMapper;

  protected volatile String cachedToken;
  protected volatile Instant tokenExpiry = Instant.EPOCH;

  protected OAuth2ClientCredentialsProvider(Builder builder) {
    this.tokenUri = builder.tokenUri;
    this.clientId = builder.clientId;
    this.clientSecret = builder.clientSecret;
    this.clientAssertionProvider = builder.clientAssertionProvider;
    this.scope = builder.scope;
    this.audience = builder.audience;
    this.resource = builder.resource;
    this.additionalParameters = builder.additionalParameters.isEmpty()
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.additionalParameters));
    this.expiryBuffer = builder.expiryBuffer;
    this.httpClient = builder.httpClient != null ? builder.httpClient : HttpClients.createDefault();
    this.objectMapper = builder.objectMapper != null ? builder.objectMapper : new ObjectMapper();
  }

  @Override
  public void close() throws IOException {
    httpClient.close();
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public void intercept(ClientRequestContext requestContext) {
    requestContext.addHeader(AUTHORIZATION, "Bearer " + getValidToken());
  }

  protected String getValidToken() {
    if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minus(expiryBuffer))) {
      return cachedToken;
    }
    synchronized (this) {
      // double-checked locking: another thread may have refreshed while we waited
      if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minus(expiryBuffer))) {
        return cachedToken;
      }
      fetchAndCacheToken();
      return cachedToken;
    }
  }

  protected void fetchAndCacheToken() {
    List<BasicNameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("grant_type", GRANT_TYPE_CLIENT_CREDENTIALS));
    params.add(new BasicNameValuePair("client_id", clientId));

    if (clientSecret != null && !clientSecret.isBlank()) {
      params.add(new BasicNameValuePair("client_secret", clientSecret));
    } else {
      params.add(new BasicNameValuePair("client_assertion_type", ASSERTION_TYPE));
      params.add(new BasicNameValuePair("client_assertion", clientAssertionProvider.getAssertion()));
    }

    if (scope != null && !scope.isBlank()) {
      params.add(new BasicNameValuePair("scope", scope));
    }
    if (audience != null && !audience.isBlank()) {
      params.add(new BasicNameValuePair("audience", audience));
    }
    if (resource != null && !resource.isBlank()) {
      params.add(new BasicNameValuePair("resource", resource));
    }
    additionalParameters.forEach((key, value) -> {
      if (key != null && !key.isBlank() && value != null) {
        params.add(new BasicNameValuePair(key, value));
      }
    });

    HttpPost request = new HttpPost(tokenUri);
    request.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

    try {
      String[] result = httpClient.execute(request, response -> {
        int statusCode = response.getCode();
        String body = response.getEntity() != null
            ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
            : "";

        if (statusCode != 200) {
          JsonNode errorJson = parseJsonSilently(body);
          String error = errorJson != null ? errorJson.path("error").asText("unknown_error") : "unknown_error";
          String desc  = errorJson != null ? errorJson.path("error_description").asText("") : "";
          throw new ExternalTaskClientException(
              "Token endpoint returned HTTP " + statusCode + ": " + error
                  + (desc.isBlank() ? "" : " - " + desc));
        }
        return new String[]{ body };
      });

      JsonNode json = objectMapper.readTree(result[0]);
      String accessToken = json.path("access_token").asText();
      if (accessToken == null || accessToken.isBlank()) {
        throw new ExternalTaskClientException(
            "Token endpoint response did not contain a valid access_token");
      }
      long expiresIn = json.path("expires_in").asLong(3600L);

      cachedToken = accessToken;
      tokenExpiry = Instant.now().plusSeconds(expiresIn);

    } catch (ExternalTaskClientException e) {
      throw LOG.oauth2TokenAcquisitionFailedException(e);
    } catch (IOException e) {
      throw LOG.oauth2TokenAcquisitionFailedException(e);
    }
  }

  private JsonNode parseJsonSilently(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException | RuntimeException ignored) {
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------

  public static class Builder {

    private String tokenUri;
    private String clientId;
    private String clientSecret;
    private ClientAssertionProvider clientAssertionProvider;
    private String scope;
    private String audience;
    private String resource;
    private Map<String, String> additionalParameters = new LinkedHashMap<>();
    private Duration expiryBuffer = Duration.ofSeconds(30);
    private CloseableHttpClient httpClient;
    private ObjectMapper objectMapper;

    /**
     * Sets the OAuth2 token endpoint URI (required).
     */
    public Builder tokenUri(String tokenUri) {
      this.tokenUri = tokenUri;
      return this;
    }

    /**
     * Sets the OAuth2 client ID (required).
     */
    public Builder clientId(String clientId) {
      this.clientId = clientId;
      return this;
    }

    /**
     * Sets the client secret for {@code client_secret_post} authentication.
     * Mutually exclusive with {@link #clientAssertionProvider(ClientAssertionProvider)}.
     */
    public Builder clientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
      return this;
    }

    /**
     * Sets a {@link ClientAssertionProvider} for JWT Bearer client authentication.
     * Mutually exclusive with {@link #clientSecret(String)}.
     *
     * @see JjwtClientAssertionProvider
     * @see AzureWorkloadIdentityAssertionProvider
     */
    public Builder clientAssertionProvider(ClientAssertionProvider clientAssertionProvider) {
      this.clientAssertionProvider = clientAssertionProvider;
      return this;
    }

    /**
     * Sets the OAuth2 scope(s) to request (optional).
     * Use {@code null} or omit to request without a scope parameter.
     */
    public Builder scope(String scope) {
      this.scope = scope;
      return this;
    }

    /**
     * Sets the OAuth2 audience parameter (optional).
     * Used by Auth0, some Keycloak configurations, and other IdPs.
     */
    public Builder audience(String audience) {
      this.audience = audience;
      return this;
    }

    /**
     * Sets the OAuth2 resource parameter (optional).
     * Used by Azure AD v1 endpoints and some other IdPs.
     */
    public Builder resource(String resource) {
      this.resource = resource;
      return this;
    }

    /**
     * Adds a custom parameter to the token request (optional).
     * Use this for IdP-specific parameters not covered by dedicated builder methods.
     */
    public Builder additionalParameter(String key, String value) {
      this.additionalParameters.put(key, value);
      return this;
    }

    /**
     * Sets all custom parameters for the token request (optional).
     * Replaces any previously added additional parameters.
     */
    public Builder additionalParameters(Map<String, String> additionalParameters) {
      this.additionalParameters = additionalParameters != null
          ? new LinkedHashMap<>(additionalParameters)
          : new LinkedHashMap<>();
      return this;
    }

    /**
     * Sets how long before token expiry a refresh should be triggered (default: 30 seconds).
     */
    public Builder expiryBuffer(Duration expiryBuffer) {
      this.expiryBuffer = expiryBuffer;
      return this;
    }

    /**
     * Sets a custom {@link CloseableHttpClient} for token requests (optional).
     * If not set, a default client is created via {@link HttpClients#createDefault()}.
     */
    public Builder httpClient(CloseableHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    /**
     * Sets a custom {@link ObjectMapper} for parsing token responses (optional).
     * If not set, a default instance is created.
     */
    public Builder objectMapper(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
      return this;
    }

    public OAuth2ClientCredentialsProvider build() {
      if (tokenUri == null || tokenUri.isBlank()) {
        throw LOG.oauth2TokenUriNullException();
      }
      if (clientId == null || clientId.isBlank()) {
        throw LOG.oauth2ClientIdNullException();
      }
      if ((clientSecret == null || clientSecret.isBlank()) && clientAssertionProvider == null) {
        throw new ExternalTaskClientException(
            "Either clientSecret or clientAssertionProvider must be configured on OAuth2ClientCredentialsProvider");
      }
      if (clientSecret != null && clientAssertionProvider != null) {
        throw new ExternalTaskClientException(
            "Only one of clientSecret or clientAssertionProvider may be set on OAuth2ClientCredentialsProvider, not both");
      }
      if (expiryBuffer != null && expiryBuffer.isNegative()) {
        throw new ExternalTaskClientException(
            "expiryBuffer must not be negative on OAuth2ClientCredentialsProvider");
      }
      return new OAuth2ClientCredentialsProvider(this);
    }
  }

}
