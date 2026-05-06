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
package org.cibseven.connect.agentconnector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cibseven.connect.agentconnector.AgentRequest;
import org.junit.Before;
import org.junit.Test;

import dev.langchain4j.mcp.client.McpClient;

/**
 * Tests for the legacy single-server fields, the new {@code mcpServers} JSON array,
 * their combinations, and absence — covering both
 * {@link AgentConnectorImpl#parseMcpServers(String)} and
 * {@link AgentConnectorImpl#createMcpClients(AgentRequest)}.
 *
 * <p>{@link CapturingConnector} overrides {@link AgentConnectorImpl#buildMcpClient}
 * to capture each (url, headers) pair so we can assert what would be sent to the
 * MCP transport — without opening any real network connection.
 */
public class AgentConnectorMcpTest {

  /** Captures every {@code buildMcpClient} call as a record of url + headers. */
  static final class CapturingConnector extends AgentConnectorImpl {
    final List<String> urls = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();

    @Override
    protected McpClient buildMcpClient(String url, Map<String, String> headers) {
      this.urls.add(url);
      this.headers.add(headers);
      return mock(McpClient.class);
    }
  }

  private CapturingConnector connector;

  @Before
  public void setUp() {
    connector = new CapturingConnector();
  }

  // ── createMcpClients: scenario coverage ──────────────────────────────────

  @Test
  public void shouldReturnEmptyListWhenNoMcpFieldsSet() {
    AgentRequest request = connector.createRequest();

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).isEmpty();
    assertThat(connector.urls).isEmpty();
  }

  @Test
  public void shouldBuildSingleClientFromLegacyFieldsOnly() {
    AgentRequest request = connector.createRequest()
        .mcpServerUrl("http://legacy/mcp")
        .mcpCustomHeaders("Authorization: Bearer abc|X-Tenant: acme");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).hasSize(1);
    assertThat(connector.urls).containsExactly("http://legacy/mcp");
    assertThat(connector.headers.get(0))
        .containsEntry("Authorization", "Bearer abc")
        .containsEntry("X-Tenant", "acme");
  }

  @Test
  public void shouldBuildSingleClientFromLegacyUrlWithoutHeaders() {
    AgentRequest request = connector.createRequest()
        .mcpServerUrl("http://legacy/mcp");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).hasSize(1);
    assertThat(connector.urls).containsExactly("http://legacy/mcp");
    assertThat(connector.headers.get(0)).isEmpty();
  }

  @Test
  public void shouldIgnoreLegacyHeadersWhenLegacyUrlIsAbsent() {
    AgentRequest request = connector.createRequest()
        .mcpCustomHeaders("Authorization: Bearer orphan");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).isEmpty();
    assertThat(connector.urls).isEmpty();
  }

  @Test
  public void shouldBuildClientsFromMcpServersJsonOnly() {
    AgentRequest request = connector.createRequest()
        .mcpServers("["
            + "{\"url\":\"http://s1/mcp\",\"headers\":{\"Authorization\":\"Bearer one\"}},"
            + "{\"url\":\"http://s2/mcp\"},"
            + "{\"url\":\"http://s3/mcp\",\"headers\":{\"X-Workspace\":\"ws3\"}}"
            + "]");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).hasSize(3);
    assertThat(connector.urls)
        .containsExactly("http://s1/mcp", "http://s2/mcp", "http://s3/mcp");
    assertThat(connector.headers.get(0)).containsExactly(Map.entry("Authorization", "Bearer one"));
    assertThat(connector.headers.get(1)).isEmpty();
    assertThat(connector.headers.get(2)).containsExactly(Map.entry("X-Workspace", "ws3"));
  }

  @Test
  public void shouldCombineLegacyAndJsonServersWithLegacyFirst() {
    AgentRequest request = connector.createRequest()
        .mcpServerUrl("http://legacy/mcp")
        .mcpCustomHeaders("Authorization: Bearer legacy")
        .mcpServers("["
            + "{\"url\":\"http://json1/mcp\",\"headers\":{\"X-Tag\":\"json1\"}},"
            + "{\"url\":\"http://json2/mcp\"}"
            + "]");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).hasSize(3);
    assertThat(connector.urls)
        .containsExactly("http://legacy/mcp", "http://json1/mcp", "http://json2/mcp");
    assertThat(connector.headers.get(0)).containsEntry("Authorization", "Bearer legacy");
    assertThat(connector.headers.get(1)).containsEntry("X-Tag", "json1");
    assertThat(connector.headers.get(2)).isEmpty();
  }

  @Test
  public void shouldReturnEmptyListWhenMcpServersIsEmptyJsonArray() {
    AgentRequest request = connector.createRequest().mcpServers("[]");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).isEmpty();
  }

  @Test
  public void shouldReturnEmptyListWhenMcpServersIsBlank() {
    AgentRequest request = connector.createRequest().mcpServers("   ");

    List<McpClient> clients = connector.createMcpClients(request);

    assertThat(clients).isEmpty();
  }

  // ── parseMcpServers: pure-static edge cases ──────────────────────────────

  @Test
  public void parseMcpServersShouldReturnEmptyListForNullOrBlankInput() {
    assertThat(AgentConnectorImpl.parseMcpServers(null)).isEmpty();
    assertThat(AgentConnectorImpl.parseMcpServers("")).isEmpty();
    assertThat(AgentConnectorImpl.parseMcpServers("   \t\n")).isEmpty();
  }

  @Test
  public void parseMcpServersShouldReturnEmptyListForEmptyJsonArray() {
    assertThat(AgentConnectorImpl.parseMcpServers("[]")).isEmpty();
  }

  @Test
  public void parseMcpServersShouldExtractUrlAndHeaders() {
    List<AgentConnectorImpl.McpServerSpec> specs = AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"http://a/mcp\",\"headers\":{\"H1\":\"v1\",\"H2\":\"v2\"}}]");

    assertThat(specs).hasSize(1);
    assertThat(specs.get(0).url).isEqualTo("http://a/mcp");
    assertThat(specs.get(0).headers)
        .containsEntry("H1", "v1")
        .containsEntry("H2", "v2");
  }

  @Test
  public void parseMcpServersShouldReturnEmptyHeadersWhenAbsent() {
    List<AgentConnectorImpl.McpServerSpec> specs = AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"http://a/mcp\"}]");

    assertThat(specs).hasSize(1);
    assertThat(specs.get(0).headers).isEmpty();
  }

  @Test
  public void parseMcpServersShouldStringifyNonStringHeaderValues() {
    List<AgentConnectorImpl.McpServerSpec> specs = AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"http://a/mcp\",\"headers\":{\"X-Count\":42,\"X-Flag\":true}}]");

    assertThat(specs.get(0).headers)
        .containsEntry("X-Count", "42")
        .containsEntry("X-Flag", "true");
  }

  @Test
  public void parseMcpServersShouldRejectMalformedJson() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers("not-json"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers");
  }

  @Test
  public void parseMcpServersShouldRejectNonArrayJson() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers("{\"url\":\"http://a/mcp\"}"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers");
  }

  @Test
  public void parseMcpServersShouldRejectEntryWithoutUrl() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"headers\":{\"H1\":\"v1\"}}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0]")
        .hasMessageContaining("url");
  }

  @Test
  public void parseMcpServersShouldRejectEntryWithEmptyUrl() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"\"}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0]")
        .hasMessageContaining("url");
  }

  @Test
  public void parseMcpServersShouldReportIndexOfFirstBadEntry() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"http://ok/mcp\"},{\"headers\":{}}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[1]");
  }

  @Test
  public void parseMcpServersShouldRejectNonObjectHeaders() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"url\":\"http://a/mcp\",\"headers\":\"not-an-object\"}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0].headers");
  }

}
