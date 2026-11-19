/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.cibseven.connect.ConnectorEndpointNotAllowedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConnectorEndpointPolicyTest {

  private static final String CONNECTOR_ID = "test-connector";
  private static final String PROPERTY = "cibseven.connect.test-connector.allowed-endpoints";
  private static final String ENV_VAR = "CIBSEVEN_CONNECT_TEST_CONNECTOR_ALLOWED_ENDPOINTS";
  private static final String GLOBAL_PROPERTY = "cibseven.connect.allowed-endpoints";
  private static final String GLOBAL_ENV_VAR = "CIBSEVEN_CONNECT_ALLOWED_ENDPOINTS";

  private final Map<String, String> environment = new HashMap<>();
  private Function<String, String> originalEnvReader;

  @BeforeEach
  public void installEnvironment() {
    originalEnvReader = ConnectorEndpointPolicy.envReader;
    ConnectorEndpointPolicy.envReader = environment::get;
  }

  @AfterEach
  public void restore() {
    ConnectorEndpointPolicy.envReader = originalEnvReader;
    System.clearProperty(PROPERTY);
    System.clearProperty(GLOBAL_PROPERTY);
  }

  private ConnectorEndpointPolicy allow(String value) {
    System.setProperty(PROPERTY, value);
    return ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);
  }

  private static void assertAllowed(ConnectorEndpointPolicy policy, String url) {
    assertThatCode(() -> policy.checkUrl(url)).as(url).doesNotThrowAnyException();
  }

  private static void assertRefused(ConnectorEndpointPolicy policy, String url) {
    assertThatThrownBy(() -> policy.checkUrl(url)).as(url)
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
  }

  @Test
  public void shouldExposeGlobalConfigurationNames() {
    assertThat(ConnectorEndpointPolicy.GLOBAL_SYSTEM_PROPERTY).isEqualTo(GLOBAL_PROPERTY);
    assertThat(ConnectorEndpointPolicy.GLOBAL_ENVIRONMENT_VARIABLE).isEqualTo(GLOBAL_ENV_VAR);
  }

  @Test
  public void shouldDeriveConfigurationNamesFromConnectorId() {
    assertThat(ConnectorEndpointPolicy.systemPropertyName("http-connector"))
        .isEqualTo("cibseven.connect.http-connector.allowed-endpoints");
    assertThat(ConnectorEndpointPolicy.environmentVariableName("http-connector"))
        .isEqualTo("CIBSEVEN_CONNECT_HTTP_CONNECTOR_ALLOWED_ENDPOINTS");
    assertThat(ConnectorEndpointPolicy.environmentVariableName("cibseven-ai-agent"))
        .isEqualTo("CIBSEVEN_CONNECT_CIBSEVEN_AI_AGENT_ALLOWED_ENDPOINTS");
  }

  @Test
  public void shouldAllowEverythingWhenNotConfigured() {
    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);

    assertThat(policy.isRestricted()).isFalse();
    assertAllowed(policy, "http://anything.test/path");
    assertAllowed(policy, "not a url");
    assertAllowed(policy, null);
    assertThatCode(() -> policy.checkEndpoint(null, null, -1)).doesNotThrowAnyException();
  }

  @Test
  public void shouldTreatBlankValueAsNotConfigured() {
    assertThat(allow("   ").isRestricted()).isFalse();
  }

  @Test
  public void shouldAllowListedOriginWithAnyPath() {
    ConnectorEndpointPolicy policy = allow("https://mcp.example.com");

    assertThat(policy.isRestricted()).isTrue();
    assertAllowed(policy, "https://mcp.example.com/mcp");
    assertAllowed(policy, "https://MCP.Example.com:443/other?x=1");
    assertAllowed(policy, "https://mcp.example.com./mcp");
  }

  @Test
  public void shouldRefuseOtherHostSchemeOrPort() {
    ConnectorEndpointPolicy policy = allow("https://mcp.example.com");

    assertRefused(policy, "https://attacker.test/mcp");
    assertRefused(policy, "http://mcp.example.com/mcp");
    assertRefused(policy, "https://mcp.example.com:8443/mcp");
    assertRefused(policy, "https://mcp.example.com.attacker.test/mcp");
  }

  @Test
  public void shouldNotBeFooledByUserInfo() {
    ConnectorEndpointPolicy policy = allow("https://mcp.example.com");

    assertRefused(policy, "https://mcp.example.com@attacker.test/mcp");
    assertRefused(policy, "https://mcp.example.com:443@attacker.test/mcp");
  }

  @Test
  public void shouldRefuseUnparseableOrRelativeTargets() {
    ConnectorEndpointPolicy policy = allow("https://mcp.example.com");

    assertRefused(policy, null);
    assertRefused(policy, "");
    assertRefused(policy, "mcp.example.com");
    assertRefused(policy, "/mcp");
    assertRefused(policy, "https://mcp.example.com/ space");
    assertThatThrownBy(() -> policy.checkUrl("/mcp")).hasMessageContaining("(invalid endpoint)");
  }

  @Test
  public void shouldMatchExplicitPort() {
    ConnectorEndpointPolicy policy = allow("http://localhost:8080");

    assertAllowed(policy, "http://localhost:8080/mcp");
    assertRefused(policy, "http://localhost/mcp");
    assertRefused(policy, "http://localhost:8081/mcp");
    assertRefused(policy, "http://127.0.0.1:8080/mcp");
  }

  @Test
  public void shouldMatchSubdomainsOfWildcardEntryOnly() {
    ConnectorEndpointPolicy policy = allow("https://*.example.com");

    assertAllowed(policy, "https://a.example.com/x");
    assertAllowed(policy, "https://a.b.example.com/x");
    assertRefused(policy, "https://example.com/x");
    assertRefused(policy, "https://badexample.com/x");
  }

  @Test
  public void shouldAcceptSeveralEntriesSeparatedByCommaOrWhitespace() {
    ConnectorEndpointPolicy policy = allow(" https://a.test,\nhttp://b.test:8080  https://c.test/ ");

    assertAllowed(policy, "https://a.test/x");
    assertAllowed(policy, "http://b.test:8080/x");
    assertAllowed(policy, "https://c.test/x");
    assertRefused(policy, "https://d.test/x");
  }

  @Test
  public void shouldIgnoreInvalidEntriesAndKeepValidOnes() {
    ConnectorEndpointPolicy policy = allow("a.test, https://user@b.test, https://c.test/path, https://*,"
        + " https://d.test?x, https://e.test");

    assertRefused(policy, "https://b.test/");
    assertRefused(policy, "https://c.test/path");
    assertRefused(policy, "https://d.test/");
    assertAllowed(policy, "https://e.test/");
  }

  @Test
  public void shouldRefuseEverythingWhenNoEntryIsValid() {
    ConnectorEndpointPolicy policy = allow("not-a-url");

    assertThat(policy.isRestricted()).isTrue();
    assertRefused(policy, "https://anything.test/");
  }

  @Test
  public void shouldCheckEndpointsWithoutUrl() {
    ConnectorEndpointPolicy policy = allow("postgresql://db.example.com");

    assertThatCode(() -> policy.checkEndpoint("postgresql", "db.example.com", 5432)).doesNotThrowAnyException();
    assertThatCode(() -> policy.checkEndpoint("postgresql", "DB.example.com", -1)).doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", "db.example.com", 5433))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", "db.example.com,attacker.test", 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", null, 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", " db.example.com", 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
  }

  @Test
  public void shouldRefuseHostsThatAreNoHostNameEvenWithWildcardEntry() {
    ConnectorEndpointPolicy policy = allow("postgresql://*.example.com, postgresql://db.example.com");

    for (String host : new String[] {"attacker.test,db.example.com", "attacker.test/db.example.com",
        "user:pw@attacker.test.db.example.com", "attacker.test db.example.com", "a..db.example.com",
        ".db.example.com", "db.example.com:5432", "attacker.test;db.example.com", "", " ", "[::1"}) {
      assertThatThrownBy(() -> policy.checkEndpoint("postgresql", host, 5432))
          .as("'" + host + "'")
          .isInstanceOf(ConnectorEndpointNotAllowedException.class)
          .hasMessageContaining("(invalid endpoint)")
          .hasMessageNotContaining("attacker")
          .hasMessageNotContaining("pw");
    }
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", "aa:bb.db.example.com", 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    ConnectorEndpointPolicy hexWildcard = allow("postgresql://*.cafe.de");
    assertThatThrownBy(() -> hexWildcard.checkEndpoint("postgresql", "aa:bb.cafe.de", 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class);
    assertThatCode(() -> policy.checkEndpoint("postgresql", "a.db.example.com", 5432)).doesNotThrowAnyException();
    assertThatCode(() -> policy.checkEndpoint("postgresql", "db.example.com.", 5432)).doesNotThrowAnyException();
  }

  @Test
  public void shouldIgnoreTooBroadWildcardEntries() {
    ConnectorEndpointPolicy policy = allow("https://*.0.0.1, https://*.com, http://*.[::1], https://*.10.0.0.1,"
        + " https://*.example.com");

    assertRefused(policy, "https://127.0.0.1/");
    assertRefused(policy, "https://attacker.com/");
    assertRefused(policy, "https://10.10.0.0.1/");
    assertAllowed(policy, "https://a.example.com/");
  }

  @Test
  public void shouldRefuseEverythingWhenOnlyTooBroadWildcardsAreConfigured() {
    ConnectorEndpointPolicy policy = allow("https://*.com");

    assertThat(policy.isRestricted()).isTrue();
    assertRefused(policy, "https://example.com/");
  }

  @Test
  public void shouldAllowHostsWithUnderscore() {
    ConnectorEndpointPolicy policy = allow("http://mcp_server:8080, https://*.svc.compose_net");

    assertAllowed(policy, "http://mcp_server:8080/mcp");
    assertAllowed(policy, "http://MCP_SERVER:8080");
    assertAllowed(policy, "https://a_b.svc.compose_net/x");
    assertRefused(policy, "http://mcp_server/mcp");
    assertRefused(policy, "http://other_server:8080/mcp");
  }

  @Test
  public void shouldRefuseAuthoritiesThatParsersSplitDifferently() {
    ConnectorEndpointPolicy policy = allow("http://mcp_server:8080, http://attacker.test:8080");

    assertRefused(policy, "http://mcp_server:8080@other_host:8080/mcp");
    assertRefused(policy, "http://a@b@mcp_server:8080/mcp");
    assertRefused(policy, "http://mcp%5Fserver:8080/mcp");
    assertRefused(policy, "http://mcp_server:80:80/mcp");
  }

  @Test
  public void shouldIgnoreUnderscoreEntriesWithUserInfo() {
    ConnectorEndpointPolicy policy = allow("http://user@mcp_server:8080");

    assertRefused(policy, "http://mcp_server:8080/mcp");
  }

  @Test
  public void shouldMatchIpv6WithOrWithoutBrackets() {
    ConnectorEndpointPolicy policy = allow("http://[::1]:8080, postgresql://[::1]");

    assertAllowed(policy, "http://[::1]:8080/mcp");
    assertRefused(policy, "http://[::2]:8080/mcp");
    assertThatCode(() -> policy.checkEndpoint("postgresql", "::1", 5432)).doesNotThrowAnyException();
    assertThatCode(() -> policy.checkEndpoint("postgresql", "[::1]", -1)).doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.checkEndpoint("postgresql", "::2", 5432))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("'postgresql://[::2]:5432'");
  }

  @Test
  public void shouldRefusePortsOutsideValidRange() {
    ConnectorEndpointPolicy policy = allow("postgresql://db.example.com, http://h:70000");

    for (int port : new int[] {0, -2, 65536, 70000}) {
      assertThatThrownBy(() -> policy.checkEndpoint("postgresql", "db.example.com", port))
          .as("port " + port)
          .isInstanceOf(ConnectorEndpointNotAllowedException.class)
          .hasMessageContaining("(invalid endpoint)");
    }
    assertRefused(policy, "http://h:70000/");
  }

  @Test
  public void shouldNameEndpointAndConfigurationButNoCredentialsInMessage() {
    ConnectorEndpointPolicy policy = allow("https://mcp.example.com");

    assertThatThrownBy(() -> policy.checkUrl("https://user:secret@attacker.test/mcp?token=secret"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("'test-connector'")
        .hasMessageContaining("'https://attacker.test:443'")
        .hasMessageContaining(PROPERTY)
        .hasMessageContaining(ENV_VAR)
        .hasMessageNotContaining("secret");
  }

  @Test
  public void shouldFallBackToEnvironmentVariable() {
    environment.put(ENV_VAR, "https://env.test");

    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);

    assertAllowed(policy, "https://env.test/");
    assertRefused(policy, "https://other.test/");
  }

  @Test
  public void shouldPreferSystemPropertyOverEnvironmentVariable() {
    environment.put(ENV_VAR, "https://env.test");

    ConnectorEndpointPolicy policy = allow("https://property.test");

    assertAllowed(policy, "https://property.test/");
    assertRefused(policy, "https://env.test/");
  }

  @Test
  public void shouldPickUpChangedConfiguration() {
    assertRefused(allow("https://first.test"), "https://second.test/");
    assertAllowed(allow("https://second.test"), "https://second.test/");

    System.clearProperty(PROPERTY);
    assertThat(ConnectorEndpointPolicy.forConnector(CONNECTOR_ID).isRestricted()).isFalse();
  }

  @Test
  public void shouldKeepConnectorsApart() {
    allow("https://only-for-test-connector.test");

    assertThat(ConnectorEndpointPolicy.forConnector("other-connector").isRestricted()).isFalse();
  }

  @Test
  public void shouldReadPostgresAsPostgresql() {
    ConnectorEndpointPolicy policy = allow("postgres://db.example.com");

    assertThatCode(() -> policy.checkEndpoint("postgresql", "db.example.com", 5432)).doesNotThrowAnyException();
    assertThatCode(() -> policy.checkEndpoint("POSTGRES", "db.example.com", -1)).doesNotThrowAnyException();
  }

  @Test
  public void shouldRequirePortForSchemeWithoutDefaultPort() {
    ConnectorEndpointPolicy withoutPort = allow("ftp://files.example.com");
    assertThat(withoutPort.isRestricted()).isTrue();
    assertRefused(withoutPort, "ftp://files.example.com/x");

    ConnectorEndpointPolicy withPort = allow("ftp://files.example.com:21");
    assertAllowed(withPort, "ftp://files.example.com:21/x");
    assertRefused(withPort, "ftp://files.example.com/x");
  }

  // ── global fallback list ────────────────────────────────────────────────

  @Test
  public void shouldUseGlobalSystemPropertyWithoutOwnValue() {
    System.setProperty(GLOBAL_PROPERTY, "https://global.test");

    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);

    assertAllowed(policy, "https://global.test/");
    assertRefused(policy, "https://other.test/");
    assertThat(ConnectorEndpointPolicy.forConnector("other-connector").isRestricted()).isTrue();
  }

  @Test
  public void shouldUseGlobalEnvironmentVariableLast() {
    environment.put(GLOBAL_ENV_VAR, "https://global-env.test");

    assertAllowed(ConnectorEndpointPolicy.forConnector(CONNECTOR_ID), "https://global-env.test/");

    System.setProperty(GLOBAL_PROPERTY, "https://global-property.test");
    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);
    assertAllowed(policy, "https://global-property.test/");
    assertRefused(policy, "https://global-env.test/");
  }

  @Test
  public void shouldPreferOwnEnvironmentVariableOverGlobalSystemProperty() {
    System.setProperty(GLOBAL_PROPERTY, "https://global.test");
    environment.put(ENV_VAR, "https://own-env.test");

    ConnectorEndpointPolicy policy = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);

    assertAllowed(policy, "https://own-env.test/");
    assertRefused(policy, "https://global.test/");
  }

  @Test
  public void shouldReplaceGlobalListWithOwnListInsteadOfMerging() {
    System.setProperty(GLOBAL_PROPERTY, "https://global.test");

    ConnectorEndpointPolicy policy = allow("https://own.test");

    assertAllowed(policy, "https://own.test/");
    assertRefused(policy, "https://global.test/");
  }

  @Test
  public void shouldLiftGlobalRestrictionWithAsterisk() {
    System.setProperty(GLOBAL_PROPERTY, "https://global.test");

    ConnectorEndpointPolicy policy = allow(" * ");

    assertThat(policy.isRestricted()).isFalse();
    assertAllowed(policy, "https://anything.test/");
    assertThat(ConnectorEndpointPolicy.forConnector("other-connector").isRestricted()).isTrue();
  }

  @Test
  public void shouldTreatAsteriskAsUnrestrictedWithoutGlobalList() {
    assertThat(allow("*").isRestricted()).isFalse();
  }

  @Test
  public void shouldIgnoreAsteriskAsPartOfList() {
    ConnectorEndpointPolicy policy = allow("*, https://a.test");

    assertThat(policy.isRestricted()).isTrue();
    assertAllowed(policy, "https://a.test/");
    assertRefused(policy, "https://b.test/");
  }

  @Test
  public void shouldNameGlobalSourceAndOwnSettingInMessage() {
    System.setProperty(GLOBAL_PROPERTY, "https://global.test");

    assertThatThrownBy(() -> ConnectorEndpointPolicy.forConnector(CONNECTOR_ID).checkUrl("https://other.test/"))
        .isInstanceOf(ConnectorEndpointNotAllowedException.class)
        .hasMessageContaining("not allowed by '" + GLOBAL_PROPERTY + "'")
        .hasMessageContaining(PROPERTY)
        .hasMessageContaining(ENV_VAR);
  }

  @Test
  public void shouldPickUpSwitchBetweenSourcesWithSameValue() {
    System.setProperty(GLOBAL_PROPERTY, "https://same.test");
    ConnectorEndpointPolicy fromGlobal = ConnectorEndpointPolicy.forConnector(CONNECTOR_ID);

    ConnectorEndpointPolicy fromOwn = allow("https://same.test");

    assertThat(fromOwn).isNotSameAs(fromGlobal);
    assertThatThrownBy(() -> fromOwn.checkUrl("https://other.test/")).hasMessageContaining("not allowed by '" + PROPERTY + "'");
  }

}
