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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import io.restassured.response.ValidatableResponse;

/**
 * The MCP REST API in configuration/userlib-ai/ (on the classpath with --ai) generates its tools
 * from the OpenAPI document of this CIB seven version and calls the REST API of Run with them.
 *
 * <ul>
 * <li>--rest --ai: the MCP REST API has only the libraries of userlib-ai/ and of the
 * cibseven-bpm-run-core uberjar. If one is missing, e.g. Jackson 2 for swagger-parser, Run does
 * not start (CIB7-2740).</li>
 * <li>--rest --webapps --ai: userlib-ai/ comes first on the loader path, so its swagger-core is
 * used by springdoc of the webclient as well (/v3/api-docs failed with NoClassDefFoundError,
 * CIB7-2737).</li>
 * </ul>
 */
@ParameterizedClass(name = "Test instance: {index}. Commands: {0}")
@MethodSource("commands")
public class McpRestApiIT {

  private static final String OPENAPI_FILE = "configuration/mcp-restapi-openapi.json";

  @Parameter(0)
  public String[] commands;
  @Parameter(1)
  public boolean webappsAvailable;

  public static Collection<Object[]> commands() {
    return Arrays.asList(new Object[][] {
      { new String[]{"--rest", "--ai"}, false },
      { new String[]{"--rest", "--webapps", "--ai"}, true }
    });
  }

  private static SpringBootManagedContainer container;

  @BeforeParameterizedClassInvocation
  public static void runStartScript(String[] commands, boolean webappsAvailable) throws IOException {
    container = new SpringBootManagedContainer(commands);

    // openapi.json of cibseven-engine-rest-openapi (test dependency) as a file for the MCP REST API
    try (InputStream openApi = McpRestApiIT.class.getClassLoader().getResourceAsStream("openapi.json")) {
      container.createConfigurationYml(OPENAPI_FILE, openApi);
    }
    String configuration;
    try (InputStream yml = McpRestApiIT.class.getClassLoader().getResourceAsStream("mcp-restapi.yml")) {
      configuration = new String(yml.readAllBytes(), StandardCharsets.UTF_8)
          .replace("@OPENAPI_FILE@", Paths.get(SpringBootManagedContainer.getRunHome(), OPENAPI_FILE).toString());
    }
    container.replaceConfigurationYml(SpringBootManagedContainer.APPLICATION_YML_PATH,
        new ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8)));

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
  public void shouldListToolsOfTheOpenApiDocument() {
    mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
      .body("error", nullValue())
      .body("result.tools.name", hasItem("getProcessEngineNames"));
  }

  @Test
  public void shouldCallTheRestApiWithATool() {
    mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"getProcessEngineNames\",\"arguments\":{}}}")
      .body("error", nullValue())
      .body("result.isError", is(false))
      .body("result.content[0].text", containsString("\"default\""));
  }

  @Test
  public void shouldServeTheOpenApiDocumentOfTheWebclient() {
    when().get(container.getBaseUrl() + "/webapp/v3/api-docs")
      .then()
      .statusCode(webappsAvailable ? 200 : 404);
  }

  private static ValidatableResponse mcp(String request) {
    return given()
        .contentType(ContentType.JSON)
        .accept("application/json, text/event-stream")
        .body(request)
      .when()
        .post(container.getBaseUrl() + "/mcp")
      .then()
        .statusCode(200)
        .contentType(ContentType.JSON);
  }
}
