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
package org.cibseven.bpm.spring.boot.starter.property;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import static org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties.joinOn;

/**
 * The {@code camunda.bpm.webapp} properties that only the legacy webapps (Cockpit, Tasklist, Admin)
 * read. They are bound next to {@link WebappProperty}, which keeps the ones of the webclient. This
 * module is never published: it only serves the tests that still drive the engine through the
 * legacy webapps.
 */
@ConfigurationProperties(WebappProperty.PREFIX)
public class LegacyWebappProperty {
  public static final String LEGACY_APP_PATH = "/camunda";

  protected String securityConfigFile = "/securityFilterRules.json";

  protected String legacyWebjarClasspath = "/META-INF/resources/webjars" + LEGACY_APP_PATH;

  protected String legacyApplicationPath = LEGACY_APP_PATH;

  @NestedConfigurationProperty
  private CsrfProperties csrf = new CsrfProperties();

  @NestedConfigurationProperty
  private SessionCookieProperties sessionCookie = new SessionCookieProperties();

  @NestedConfigurationProperty
  protected HeaderSecurityProperties headerSecurity = new HeaderSecurityProperties();

  @NestedConfigurationProperty
  protected AuthenticationProperties auth = new AuthenticationProperties();

  public String getSecurityConfigFile() {
    return securityConfigFile;
  }

  public void setSecurityConfigFile(String securityConfigFile) {
    this.securityConfigFile = securityConfigFile;
  }

  public String getLegacyWebjarClasspath() {
    return legacyWebjarClasspath;
  }

  public void setLegacyWebjarClasspath(String legacyWebjarClasspath) {
    this.legacyWebjarClasspath = legacyWebjarClasspath;
  }

  public String getLegacyApplicationPath() {
    return legacyApplicationPath;
  }

  public void setLegacyApplicationPath(String legacyApplicationPath) {
    this.legacyApplicationPath = WebappProperty.sanitizeApplicationPath(legacyApplicationPath);
  }

  public CsrfProperties getCsrf() {
    return csrf;
  }

  public void setCsrf(CsrfProperties csrf) {
    this.csrf = csrf;
  }

  public SessionCookieProperties getSessionCookie() {
    return sessionCookie;
  }

  public void setSessionCookie(SessionCookieProperties sessionCookie) {
    this.sessionCookie = sessionCookie;
  }

  public HeaderSecurityProperties getHeaderSecurity() {
    return headerSecurity;
  }

  public void setHeaderSecurity(HeaderSecurityProperties headerSecurity) {
    this.headerSecurity = headerSecurity;
  }

  public AuthenticationProperties getAuth() {
    return auth;
  }

  public void setAuth(AuthenticationProperties authentication) {
    this.auth = authentication;
  }

  @Override
  public String toString() {
    return joinOn(this.getClass())
      .add("securityConfigFile='" + securityConfigFile + '\'')
      .add("legacyWebjarClasspath='" + legacyWebjarClasspath + '\'')
      .add("legacyApplicationPath='" + legacyApplicationPath + '\'')
      .add("csrf='" + csrf + '\'')
      .add("headerSecurityProperties='" + headerSecurity + '\'')
      .add("sessionCookie='" + sessionCookie + '\'')
      .add("auth='" + auth + '\'')
      .toString();
  }
}
