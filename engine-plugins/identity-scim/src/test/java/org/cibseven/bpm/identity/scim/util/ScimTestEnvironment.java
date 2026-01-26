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
package org.cibseven.bpm.identity.scim.util;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * SCIM test environment using WireMock to simulate a SCIM 2.0 server.
 */
public class ScimTestEnvironment {

  protected WireMockServer wireMockServer;
  protected int port;
  protected String serverUrl;

  private int numberOfUsersCreated = 0;
  private int numberOfGroupsCreated = 0;

  public ScimTestEnvironment() {
    this(0); // Use dynamic port
  }

  public ScimTestEnvironment(int port) {
    this.port = port;
  }

  public void init() {
    WireMockConfiguration config = WireMockConfiguration.wireMockConfig();
    if (port > 0) {
      config.port(port);
    } else {
      config.dynamicPort();
    }
    
    wireMockServer = new WireMockServer(config);
    wireMockServer.start();
    
    this.port = wireMockServer.port();
    this.serverUrl = "http://localhost:" + this.port;
    
    setupScimEndpoints();
  }

  protected void setupScimEndpoints() {
    // Setup test data
    setupUsers();
    setupGroups();
  }

  protected void setupUsers() {
    // User 1: Oscar
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"oscar\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"user-oscar\",\n" +
                "    \"userName\": \"oscar\",\n" +
                "    \"name\": {\n" +
                "      \"givenName\": \"Oscar\",\n" +
                "      \"familyName\": \"The Crouch\"\n" +
                "    },\n" +
                "    \"emails\": [{\n" +
                "      \"value\": \"oscar@camunda.org\",\n" +
                "      \"type\": \"work\"\n" +
                "    }],\n" +
                "    \"displayName\": \"Oscar The Crouch\"\n" +
                "  }]\n" +
                "}")));

    // User 2: Monster
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"monster\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"user-monster\",\n" +
                "    \"userName\": \"monster\",\n" +
                "    \"name\": {\n" +
                "      \"givenName\": \"Cookie\",\n" +
                "      \"familyName\": \"Monster\"\n" +
                "    },\n" +
                "    \"emails\": [{\n" +
                "      \"value\": \"monster@camunda.org\",\n" +
                "      \"type\": \"work\"\n" +
                "    }],\n" +
                "    \"displayName\": \"Cookie Monster\"\n" +
                "  }]\n" +
                "}")));

    // User 3: Daniel
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"daniel\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"user-daniel\",\n" +
                "    \"userName\": \"daniel\",\n" +
                "    \"name\": {\n" +
                "      \"givenName\": \"Daniel\",\n" +
                "      \"familyName\": \"Meyer\"\n" +
                "    },\n" +
                "    \"emails\": [{\n" +
                "      \"value\": \"daniel@camunda.org\",\n" +
                "      \"type\": \"work\"\n" +
                "    }],\n" +
                "    \"displayName\": \"Daniel Meyer\"\n" +
                "  }]\n" +
                "}")));

    // All users query (no filter or empty filter)
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 3,\n" +
                "  \"startIndex\": 1,\n" +
                "  \"itemsPerPage\": 3,\n" +
                "  \"Resources\": [\n" +
                "    {\n" +
                "      \"id\": \"user-oscar\",\n" +
                "      \"userName\": \"oscar\",\n" +
                "      \"name\": {\n" +
                "        \"givenName\": \"Oscar\",\n" +
                "        \"familyName\": \"The Crouch\"\n" +
                "      },\n" +
                "      \"emails\": [{\n" +
                "        \"value\": \"oscar@camunda.org\",\n" +
                "        \"type\": \"work\"\n" +
                "      }],\n" +
                "      \"displayName\": \"Oscar The Crouch\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"user-monster\",\n" +
                "      \"userName\": \"monster\",\n" +
                "      \"name\": {\n" +
                "        \"givenName\": \"Cookie\",\n" +
                "        \"familyName\": \"Monster\"\n" +
                "      },\n" +
                "      \"emails\": [{\n" +
                "        \"value\": \"monster@camunda.org\",\n" +
                "        \"type\": \"work\"\n" +
                "      }],\n" +
                "      \"displayName\": \"Cookie Monster\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"user-daniel\",\n" +
                "      \"userName\": \"daniel\",\n" +
                "      \"name\": {\n" +
                "        \"givenName\": \"Daniel\",\n" +
                "        \"familyName\": \"Meyer\"\n" +
                "      },\n" +
                "      \"emails\": [{\n" +
                "        \"value\": \"daniel@camunda.org\",\n" +
                "        \"type\": \"work\"\n" +
                "      }],\n" +
                "      \"displayName\": \"Daniel Meyer\"\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    // Non-existing user
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"non-existing\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 0,\n" +
                "  \"Resources\": []\n" +
                "}")));

    // Multiple user IDs (OR query)
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("(userName eq \"oscar\" or userName eq \"monster\")"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 2,\n" +
                "  \"Resources\": [\n" +
                "    {\n" +
                "      \"id\": \"user-oscar\",\n" +
                "      \"userName\": \"oscar\",\n" +
                "      \"name\": {\n" +
                "        \"givenName\": \"Oscar\",\n" +
                "        \"familyName\": \"The Crouch\"\n" +
                "      },\n" +
                "      \"emails\": [{\n" +
                "        \"value\": \"oscar@camunda.org\",\n" +
                "        \"type\": \"work\"\n" +
                "      }],\n" +
                "      \"displayName\": \"Oscar The Crouch\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"user-monster\",\n" +
                "      \"userName\": \"monster\",\n" +
                "      \"name\": {\n" +
                "        \"givenName\": \"Cookie\",\n" +
                "        \"familyName\": \"Monster\"\n" +
                "      },\n" +
                "      \"emails\": [{\n" +
                "        \"value\": \"monster@camunda.org\",\n" +
                "        \"type\": \"work\"\n" +
                "      }],\n" +
                "      \"displayName\": \"Cookie Monster\"\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    numberOfUsersCreated = 3;
  }

  protected void setupGroups() {
    // Group 1: development
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("displayName eq \"development\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"group-development\",\n" +
                "    \"displayName\": \"development\",\n" +
                "    \"members\": [\n" +
                "      {\"value\": \"oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "      {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    // Group 2: management
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("displayName eq \"management\""))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"group-management\",\n" +
                "    \"displayName\": \"management\",\n" +
                "    \"members\": [\n" +
                "      {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    // All groups query
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 2,\n" +
                "  \"startIndex\": 1,\n" +
                "  \"itemsPerPage\": 2,\n" +
                "  \"Resources\": [\n" +
                "    {\n" +
                "      \"id\": \"group-development\",\n" +
                "      \"displayName\": \"development\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "        {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"group-management\",\n" +
                "      \"displayName\": \"management\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    // Groups by user filter - oscar
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("members[value eq \"oscar\"]"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"group-development\",\n" +
                "    \"displayName\": \"development\",\n" +
                "    \"members\": [\n" +
                "      {\"value\": \"oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "      {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    // Groups by user filter - daniel (member of both)
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("members[value eq \"daniel\"]"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 2,\n" +
                "  \"Resources\": [\n" +
                "    {\n" +
                "      \"id\": \"group-development\",\n" +
                "      \"displayName\": \"development\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "        {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"group-management\",\n" +
                "      \"displayName\": \"management\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    // Get group by ID
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups/development"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"id\": \"group-development\",\n" +
                "  \"displayName\": \"development\",\n" +
                "  \"members\": [\n" +
                "    {\"value\": \"oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "    {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "  ]\n" +
                "}")));

    numberOfGroupsCreated = 2;
  }

  public void shutdown() {
    if (wireMockServer != null) {
      wireMockServer.stop();
    }
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public int getPort() {
    return port;
  }

  public int getTotalNumberOfUsersCreated() {
    return numberOfUsersCreated;
  }

  public int getTotalNumberOfGroupsCreated() {
    return numberOfGroupsCreated;
  }
}
