/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The {@code camunda.bpm.oauth2} properties that only the OAuth2 login of the legacy webapps reads.
 * They are bound next to {@link OAuth2Properties}. This module is never published: it only serves
 * the tests that still drive the engine through the legacy webapps.
 */
@ConfigurationProperties(OAuth2Properties.PREFIX)
public class LegacyWebappOAuth2Properties {

  /**
   * OAuth2 SSO logout configuration.
   */
  @NestedConfigurationProperty
  private OAuth2SSOLogoutProperties ssoLogout = new OAuth2SSOLogoutProperties();

  public static class OAuth2SSOLogoutProperties {
    /**
     * Enable SSO Logout. Default {@code false}.
     */
    private boolean enabled = false;

    /**
     * URI the user is redirected after SSO logout from the provider. Default {@code {baseUrl}}.
     */
    private String postLogoutRedirectUri = "{baseUrl}";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getPostLogoutRedirectUri() {
      return postLogoutRedirectUri;
    }

    public void setPostLogoutRedirectUri(String postLogoutRedirectUri) {
      this.postLogoutRedirectUri = postLogoutRedirectUri;
    }
  }

  public OAuth2SSOLogoutProperties getSsoLogout() {
    return ssoLogout;
  }

  public void setSsoLogout(OAuth2SSOLogoutProperties ssoLogout) {
    this.ssoLogout = ssoLogout;
  }
}
