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
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

/**
 * Tests for the {@code mcpServers} JSON array and its edge cases — covering both
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

  @BeforeEach
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

  // ── buildToolProvenance — Art. 26 data-flow disclosure (CIB7-1399 + CIB7-1407) ─

  /** Pure-Java @Tool class used as a local-side fixture for provenance tests. */
  public static class LocalProvenanceTool {
    @Tool("startProcess")
    public String startProcess(@P("Process key") String key) { return key; }

    @Tool   // no explicit name — should default to the method name "ping"
    public String ping() { return "pong"; }
  }

  /**
   * Builds the same {@code Map<McpClient, String>} the production
   * {@code execute()} passes to {@link AgentConnectorImpl#buildToolProvenance}
   * — keys clients to their resolved per-server prefix so tests don't have to
   * duplicate the resolution logic.
   */
  private static Map<McpClient, String> prefixMap(
      List<McpClient> clients,
      List<AgentConnectorImpl.McpServerSpec> specs) {
    Map<McpClient, String> map = new LinkedHashMap<>();
    for (int i = 0; i < clients.size() && i < specs.size(); i++) {
      map.put(clients.get(i),
          AgentConnectorImpl.resolveServerPrefix(specs.get(i), i + 1));
    }
    return map;
  }

  @Test
  public void buildToolProvenanceShouldRecordLocalToolsByAnnotationOrMethodName() {
    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.singletonList(new LocalProvenanceTool()),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyMap());

    assertThat(provenance).containsOnlyKeys("startProcess", "ping");
    assertThat(provenance.get("startProcess")).containsEntry("kind", "local");
    assertThat(provenance.get("ping")).containsEntry("kind", "local");
  }

  @Test
  public void buildToolProvenanceShouldKeyMcpEntriesByPrefixedNameAndCarryServerMetadata() {
    McpClient client = mock(McpClient.class);
    when(client.listTools()).thenReturn(Arrays.asList(
        ToolSpecification.builder().name("search").build(),
        ToolSpecification.builder().name("lookup").build()));
    List<AgentConnectorImpl.McpServerSpec> specs =
        AgentConnectorImpl.parseMcpServers("[{\"url\":\"https://mcp.example/v1\"}]");
    List<McpClient> clients = Collections.singletonList(client);

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(), clients, specs, prefixMap(clients, specs));

    // URL has no explicit port → slug = host with non-alnum → "-": "mcp-example".
    assertThat(provenance).containsOnlyKeys("mcp-example__search", "mcp-example__lookup");
    assertThat(provenance.get("mcp-example__search"))
        .containsEntry("kind", "mcp")
        .containsEntry("url", "https://mcp.example/v1")
        .containsEntry("server", "mcp-example")
        .containsEntry("originalToolName", "search");
    assertThat(provenance.get("mcp-example__lookup"))
        .containsEntry("originalToolName", "lookup")
        .containsEntry("server", "mcp-example");
  }

  @Test
  public void buildToolProvenanceShouldUseExplicitServerNameWhenProvided() {
    McpClient client = mock(McpClient.class);
    when(client.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("foo").build()));
    List<AgentConnectorImpl.McpServerSpec> specs =
        AgentConnectorImpl.parseMcpServers(
            "[{\"name\":\"engine\",\"url\":\"https://mcp.example/v1\"}]");
    List<McpClient> clients = Collections.singletonList(client);

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(), clients, specs, prefixMap(clients, specs));

    // Explicit "name":"engine" wins over the URL-derived slug.
    assertThat(provenance).containsOnlyKeys("engine__foo");
    assertThat(provenance.get("engine__foo"))
        .containsEntry("server", "engine")
        .containsEntry("originalToolName", "foo");
  }

  @Test
  public void buildToolProvenanceShouldTrackBothServersWhenToolNamesCollide() {
    // The whole point of CIB7-1407: two MCP servers exposing the same tool
    // name no longer collide — they get distinct prefixed keys instead of
    // LangChain4j's IllegalConfigurationException.
    McpClient first = mock(McpClient.class);
    when(first.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("search").build()));
    McpClient second = mock(McpClient.class);
    when(second.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("search").build()));
    List<AgentConnectorImpl.McpServerSpec> specs =
        AgentConnectorImpl.parseMcpServers(
            "[{\"name\":\"primary\",\"url\":\"https://primary/v1\"},"
            + " {\"name\":\"secondary\",\"url\":\"https://secondary/v1\"}]");
    List<McpClient> clients = Arrays.asList(first, second);

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(), clients, specs, prefixMap(clients, specs));

    assertThat(provenance).containsOnlyKeys("primary__search", "secondary__search");
    assertThat(provenance.get("primary__search"))
        .containsEntry("url", "https://primary/v1")
        .containsEntry("server", "primary")
        .containsEntry("originalToolName", "search");
    assertThat(provenance.get("secondary__search"))
        .containsEntry("url", "https://secondary/v1")
        .containsEntry("server", "secondary")
        .containsEntry("originalToolName", "search");
  }

  @Test
  public void buildToolProvenanceShouldDistinguishLocalAndMcpEvenWhenOriginalNameMatches() {
    // Local @Tool registers "startProcess"; MCP server also advertises
    // "startProcess". With prefixing, no collision: MCP becomes
    // "<prefix>__startProcess", local keeps its unprefixed name.
    McpClient client = mock(McpClient.class);
    when(client.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("startProcess").build()));
    List<AgentConnectorImpl.McpServerSpec> specs =
        AgentConnectorImpl.parseMcpServers("[{\"url\":\"https://mcp.example/v1\"}]");
    List<McpClient> clients = Collections.singletonList(client);

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.singletonList(new LocalProvenanceTool()),
        clients, specs, prefixMap(clients, specs));

    assertThat(provenance).containsOnlyKeys(
        "startProcess", "ping", "mcp-example__startProcess");
    assertThat(provenance.get("startProcess"))
        .containsEntry("kind", "local")
        .doesNotContainKey("url");
    assertThat(provenance.get("mcp-example__startProcess"))
        .containsEntry("kind", "mcp")
        .containsEntry("server", "mcp-example")
        .containsEntry("originalToolName", "startProcess");
  }

  @Test
  public void buildToolProvenanceShouldSurviveAnMcpClientFailing() {
    McpClient healthy = mock(McpClient.class);
    when(healthy.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("lookup").build()));
    McpClient broken = mock(McpClient.class);
    when(broken.listTools()).thenThrow(new RuntimeException("connection refused"));
    List<AgentConnectorImpl.McpServerSpec> specs =
        AgentConnectorImpl.parseMcpServers(
            "[{\"url\":\"https://broken/v1\"},{\"url\":\"https://healthy/v1\"}]");
    List<McpClient> clients = Arrays.asList(broken, healthy);

    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(), clients, specs, prefixMap(clients, specs));

    assertThat(provenance).containsOnlyKeys("healthy__lookup");
    assertThat(provenance.get("healthy__lookup"))
        .containsEntry("url", "https://healthy/v1")
        .containsEntry("server", "healthy");
  }

  @Test
  public void buildToolProvenanceShouldFallBackToMcpPrefixAndUnknownUrlWhenSpecsMissing() {
    McpClient client = mock(McpClient.class);
    when(client.listTools()).thenReturn(Collections.singletonList(
        ToolSpecification.builder().name("search").build()));

    // Test stub of createMcpClients that returns a client without a matching
    // mcpServers JSON. The audit must not crash; fall back to the literal
    // "mcp" prefix and url="unknown".
    Map<String, Map<String, Object>> provenance = AgentConnectorImpl.buildToolProvenance(
        Collections.emptyList(),
        Collections.singletonList(client),
        Collections.emptyList(),
        Collections.emptyMap());

    assertThat(provenance).containsOnlyKeys("mcp__search");
    assertThat(provenance.get("mcp__search"))
        .containsEntry("kind", "mcp")
        .containsEntry("url", "unknown")
        .containsEntry("server", "mcp")
        .containsEntry("originalToolName", "search");
  }

  // ── parseMcpServers — name field (CIB7-1407) ─────────────────────────────

  @Test
  public void parseMcpServersShouldAcceptOptionalNameField() {
    List<AgentConnectorImpl.McpServerSpec> specs = AgentConnectorImpl.parseMcpServers(
        "[{\"name\":\"engine\",\"url\":\"http://localhost:8081/mcp\"},"
        + " {\"url\":\"http://localhost:8082/mcp\"}]");

    assertThat(specs).hasSize(2);
    assertThat(specs.get(0).name).isEqualTo("engine");
    assertThat(specs.get(0).url).isEqualTo("http://localhost:8081/mcp");
    assertThat(specs.get(1).name).isNull();
  }

  @Test
  public void parseMcpServersShouldRejectEmptyName() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"name\":\"\",\"url\":\"http://localhost:8081/mcp\"}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0]")
        .hasMessageContaining("'name'");
  }

  @Test
  public void parseMcpServersShouldRejectNameWithInvalidCharacters() {
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"name\":\"bad name\",\"url\":\"http://localhost:8081/mcp\"}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0].name")
        .hasMessageContaining("invalid");
  }

  @Test
  public void parseMcpServersShouldRejectNameLongerThan32Chars() {
    String tooLong = "a".repeat(33);
    assertThatThrownBy(() -> AgentConnectorImpl.parseMcpServers(
        "[{\"name\":\"" + tooLong + "\",\"url\":\"http://localhost:8081/mcp\"}]"))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("mcpServers[0].name");
  }

  // ── resolveServerPrefix (CIB7-1407) ──────────────────────────────────────

  @Test
  public void resolveServerPrefixShouldReturnExplicitNameWhenSet() {
    AgentConnectorImpl.McpServerSpec spec =
        AgentConnectorImpl.parseMcpServers(
            "[{\"name\":\"engine\",\"url\":\"https://anything/v1\"}]").get(0);

    assertThat(AgentConnectorImpl.resolveServerPrefix(spec, 1)).isEqualTo("engine");
  }

  @Test
  public void resolveServerPrefixShouldDeriveSlugFromUrlHostAndPort() {
    AgentConnectorImpl.McpServerSpec spec =
        AgentConnectorImpl.parseMcpServers(
            "[{\"url\":\"http://localhost:8081/mcp\"}]").get(0);

    assertThat(AgentConnectorImpl.resolveServerPrefix(spec, 1)).isEqualTo("localhost-8081");
  }

  @Test
  public void resolveServerPrefixShouldDeriveSlugFromHostWhenNoExplicitPort() {
    AgentConnectorImpl.McpServerSpec spec =
        AgentConnectorImpl.parseMcpServers(
            "[{\"url\":\"https://mcp.example.com/v1\"}]").get(0);

    assertThat(AgentConnectorImpl.resolveServerPrefix(spec, 1))
        .isEqualTo("mcp-example-com");
  }

  @Test
  public void resolveServerPrefixShouldCapSlugAt32Chars() {
    String longHost = "a".repeat(50) + ".example.com";
    AgentConnectorImpl.McpServerSpec spec =
        AgentConnectorImpl.parseMcpServers(
            "[{\"url\":\"https://" + longHost + "/v1\"}]").get(0);

    String prefix = AgentConnectorImpl.resolveServerPrefix(spec, 1);
    assertThat(prefix).hasSize(32);
    assertThat(prefix).matches("[a-zA-Z0-9_-]{1,32}");
  }

  @Test
  public void resolveServerPrefixShouldFallBackToIndexedNameOnUnparseableUrl() {
    // URI.create accepts most strings, but a URL without a host (e.g. just
    // "not a url" containing whitespace) throws IllegalArgumentException.
    AgentConnectorImpl.McpServerSpec spec =
        new AgentConnectorImpl.McpServerSpec("not a url", Collections.emptyMap());

    assertThat(AgentConnectorImpl.resolveServerPrefix(spec, 3)).isEqualTo("mcp3");
  }

}
