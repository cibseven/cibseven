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
package org.cibseven.bpm.client.spring.boot.starter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for OAuth2 client credentials flow authentication.
 *
 * <p>Configure under the {@code camunda.bpm.client.oauth2} prefix. Exactly one of
 * {@code clientSecret} or {@code assertion} must be set.</p>
 *
 */
public class OAuth2Properties {

  /**
   * OAuth2 token endpoint URI. Required.
   */
  private String tokenUri;

  /**
   * OAuth2 client ID. Required.
   */
  private String clientId;

  /**
   * Client secret for {@code client_secret_post} authentication.
   * Mutually exclusive with {@link #assertion}.
   */
  private String clientSecret;

  /**
   * OAuth2 scope(s) to request. Optional.
   * For Azure Entra ID use {@code {resource}/.default}, e.g. {@code api://my-api/.default}.
   */
  private String scope;

  /**
   * OAuth2 audience parameter. Optional.
   * Used by Auth0, some Keycloak configurations, and other IdPs.
   */
  private String audience;

  /**
   * OAuth2 resource parameter. Optional.
   * Used by Azure AD v1 endpoints and some other IdPs.
   */
  private String resource;

  /**
   * Additional custom parameters to send with the token request. Optional.
   * Use this for IdP-specific parameters not covered by dedicated properties.
   */
  private Map<String, String> additionalParameters = new LinkedHashMap<>();

  /**
   * How many seconds before token expiry a refresh should be triggered. Defaults to 30.
   */
  private long expiryBufferSeconds = 30L;

  /**
   * JWT client assertion configuration. Mutually exclusive with {@link #clientSecret}.
   */
  @NestedConfigurationProperty
  private AssertionProperties assertion;

  public String getTokenUri() {
    return tokenUri;
  }

  public void setTokenUri(String tokenUri) {
    this.tokenUri = tokenUri;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getAudience() {
    return audience;
  }

  public void setAudience(String audience) {
    this.audience = audience;
  }

  public String getResource() {
    return resource;
  }

  public void setResource(String resource) {
    this.resource = resource;
  }

  public Map<String, String> getAdditionalParameters() {
    return additionalParameters;
  }

  public void setAdditionalParameters(Map<String, String> additionalParameters) {
    this.additionalParameters = additionalParameters;
  }

  public long getExpiryBufferSeconds() {
    return expiryBufferSeconds;
  }

  public void setExpiryBufferSeconds(long expiryBufferSeconds) {
    this.expiryBufferSeconds = expiryBufferSeconds;
  }

  public AssertionProperties getAssertion() {
    return assertion;
  }

  public void setAssertion(AssertionProperties assertion) {
    this.assertion = assertion;
  }

  // ---------------------------------------------------------------------------
  // Nested: AssertionProperties
  // ---------------------------------------------------------------------------

  /**
   * Configuration for JWT Bearer client assertion.
   */
  public static class AssertionProperties {

    /**
     * The type of assertion provider to use.
     */
    private AssertionType type;

    /**
     * Location of the PEM-encoded PKCS#8 private key file.
     * Supports Spring resource prefixes: {@code classpath:}, {@code file:}.
     * Required for {@link AssertionType#JJWT}.
     * Not used for {@link AssertionType#AZURE_WORKLOAD_IDENTITY}.
     */
    private String keyLocation;

    public AssertionType getType() {
      return type;
    }

    public void setType(AssertionType type) {
      this.type = type;
    }

    public String getKeyLocation() {
      return keyLocation;
    }

    public void setKeyLocation(String keyLocation) {
      this.keyLocation = keyLocation;
    }

    /**
     * Supported JWT client assertion types.
     */
    public enum AssertionType {

      /**
       * Signs assertions via JJWT, auto-detecting the algorithm from the key type
       * (RS256 for RSA, ES256 for EC P-256).
       * Requires {@code key-location} to point to a PEM-encoded private key.
       * Requires {@code io.jsonwebtoken:jjwt-api} at compile time and
       * {@code io.jsonwebtoken:jjwt-impl} at runtime.
       */
      JJWT,

      /**
       * Uses the Azure Workload Identity federated token (reads the file referenced by
       * the {@code AZURE_FEDERATED_TOKEN_FILE} environment variable, injected by the
       * Azure Workload Identity mutating webhook).
       * No key configuration required.
       */
      AZURE_WORKLOAD_IDENTITY
    }
  }

}
