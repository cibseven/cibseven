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
package org.cibseven.bpm;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PluginsRootResourceIT extends AbstractWebIntegrationTest {

  @BeforeEach
  public void createClient() throws Exception {
    createClient(getWebappCtxPath());
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> getAssets() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("app/plugin.js", true),
        org.junit.jupiter.params.provider.Arguments.of("app/plugin.css", true),
        org.junit.jupiter.params.provider.Arguments.of("app/asset.js", false),
        org.junit.jupiter.params.provider.Arguments.of("../..", false),
        org.junit.jupiter.params.provider.Arguments.of("../../annotations-api.jar", false)
    );
  }

  @ParameterizedTest(name = "Test instance: {index}. Asset: {0}, Allowed: {1}")
  @MethodSource("getAssets")
  public void shouldGetAssetIfAllowed(String assetName, boolean assetAllowed) {
    // when
    HttpResponse<String> response = Unirest.get(appBasePath + "api/admin/plugin/adminPlugins/static/" + assetName).asString();

    // then
    assertResponse(assetName, assetAllowed, response);
  }

  protected void assertResponse(String asset, boolean assetAllowed, HttpResponse<String> response) {
    if (assetAllowed) {
      assertEquals(200, response.getStatus());
    } else {
      assertEquals(403, response.getStatus());
      assertTrue(response.getHeaders().getFirst("Content-Type").startsWith("application/json"));
      String responseEntity = response.getBody();
      assertTrue(responseEntity.contains("\"type\":\"RestException\""));
      assertTrue(responseEntity.contains("\"message\":\"Not allowed to load the following file '" + asset + "'.\""));
    }
  }

}