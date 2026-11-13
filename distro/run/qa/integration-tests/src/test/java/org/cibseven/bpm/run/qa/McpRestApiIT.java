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
package org.cibseven.bpm.run.qa;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

import org.cibseven.bpm.run.qa.util.SpringBootManagedContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;

/**
 * The MCP REST API of Run with configuration/default.yml and production.yml as shipped.
 * MCP_ENABLED=true switches it on: the start script puts configuration/userlib-ai/ on the
 * classpath, the configuration turns on the MCP server and the library, which generates its tools
 * from openapi.json of this version (on the classpath) and calls the REST API of Run with them.
 *
 * <ul>
 * <li>--rest, MCP_ENABLED=true: the MCP REST API has only the libraries of userlib-ai/ and of
 * the cibseven-bpm-run-core uberjar. If one is missing, e.g. Jackson 2 for swagger-parser, Run
 * does not start (CIB7-2740).</li>
 * <li>--rest --webapps, MCP_ENABLED=true: userlib-ai/ comes first on the loader path, so its
 * swagger-core is used by springdoc of the webclient as well (/v3/api-docs failed with
 * NoClassDefFoundError, CIB7-2737).</li>
 * <li>--rest --ai without MCP_ENABLED (the AI agent on, as in the Docker image): no MCP
 * endpoint.</li>
 * <li>--production: the same switch in production.yml. The REST API requires authentication
 * there, and a tool call without credentials must not get around it.</li>
 * </ul>
 */
@ParameterizedClass(name = "Test instance: {index}. Commands: {0}, MCP_ENABLED: {1}")
@MethodSource("commands")
public class McpRestApiIT {

  private static final String SHIPPED_CONFIGURATION = "run.shipped.configuration";
  private static final String PRODUCTION = "--production";
  /** JUnit does not pass null to a parameterized class, hence a marker for an unset variable. */
  private static final String NOT_SET = "not set";

  @Parameter(0)
  public String[] commands;
  @Parameter(1)
  public String mcpEnabledVariable;
  @Parameter(2)
  public boolean mcpAvailable;
  @Parameter(3)
  public boolean webappsAvailable;

  public static Collection<Object[]> commands() {
    return Arrays.asList(new Object[][] {
      { new String[]{"--rest"}, "true", true, false },
      { new String[]{"--rest", "--webapps"}, "true", true, true },
      { new String[]{"--rest", "--ai"}, NOT_SET, false, false },
      { new String[]{PRODUCTION, "--rest"}, "true", true, false },
      { new String[]{PRODUCTION, "--rest", "--ai"}, NOT_SET, false, false }
    });
  }

  private static SpringBootManagedContainer container;

  @BeforeParameterizedClassInvocation
  public static void runStartScript(String[] commands, String mcpEnabledVariable, boolean mcpAvailable,
      boolean webappsAvailable) throws Exception {
    container = new SpringBootManagedContainer(commands);
    Path shipped = Paths.get(System.getProperty(SHIPPED_CONFIGURATION));
    if (isProduction(commands)) {
      try (InputStream production = Files.newInputStream(shipped.resolve("production.yml"))) {
        container.createConfigurationYml("configuration/production.yml", production);
      }
    } else {
      try (InputStream defaults = Files.newInputStream(shipped.resolve("default.yml"))) {
        container.replaceConfigurationYml(SpringBootManagedContainer.APPLICATION_YML_PATH, defaults);
      }
    }
    container.setEnvironmentVariable("MCP_ENABLED", NOT_SET.equals(mcpEnabledVariable) ? null : mcpEnabledVariable);
    container.setEnvironmentVariable("AI_AGENT_ENABLED", null);

    try {
      container.start();
    } catch (Exception e) {
      throw new RuntimeException("Cannot start managed Spring Boot application!", e);
    }
  }

  @AfterParameterizedClassInvocation
  public static void stopApp() {
    try {
      if (container != null) {
        container.stop();
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot stop managed Spring Boot application!", e);
    } finally {
      container = null;
    }
  }

  @Test
  public void shouldListToolsOfTheRestApi() {
    Response response = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    if (mcpAvailable) {
      response.then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("error", nullValue())
        .body("result.tools.name", hasItem("getProcessEngineNames"));
    } else {
      response.then()
        .statusCode(404);
    }
  }

  @Test
  public void shouldCallTheRestApiWithATool() {
    assumeTrue(mcpAvailable, "MCP is switched off");
    // GET /process-definition/count; unlike GET /engine it needs authentication when the REST API has it on
    ValidatableResponse response = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"getProcessDefinitionsCount\",\"arguments\":{}}}")
      .then()
      .statusCode(200)
      .contentType(ContentType.JSON)
      .body("error", nullValue());
    if (isProduction(commands)) {
      // the request has no credentials: the REST API rejects it, the MCP endpoint does not get around it
      response.body("result.isError", is(true));
    } else {
      response
        .body("result.isError", is(false))
        .body("result.content[0].text", containsString("\"count\""));
    }
  }

  @Test
  public void shouldServeTheOpenApiDocumentOfTheWebclient() {
    when().get(container.getBaseUrl() + "/webapp/v3/api-docs")
      .then()
      .statusCode(webappsAvailable ? 200 : 404);
  }

  private static boolean isProduction(String[] commands) {
    return Arrays.asList(commands).contains(PRODUCTION);
  }

  private static Response mcp(String request) {
    return given()
        .contentType(ContentType.JSON)
        .accept("application/json, text/event-stream")
        .body(request)
      .when()
        .post(container.getBaseUrl() + "/mcp");
  }
}
