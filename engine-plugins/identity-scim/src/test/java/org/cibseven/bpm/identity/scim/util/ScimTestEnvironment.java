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
    this.port = 0; // Use dynamic port by default
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
    String oscarBody = "{\n" +
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
            "}";
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"oscar\""))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody(oscarBody)));

    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("id eq \"user-oscar\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(oscarBody)));  
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("name.givenName eq \"Oscar\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(oscarBody)));
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("emails[type eq \"work\"].value eq \"oscar@camunda.org\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(oscarBody)));    
    
    // User 2: Monster
    String monsterBody = "{\n" +
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
            "}";
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"monster\""))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody(monsterBody)));
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("id eq \"user-monster\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(monsterBody)));

    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("name.givenName eq \"Cookie\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(monsterBody)));

    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("name.familyName eq \"Monster\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(monsterBody)));
    
   
    // User 3: Daniel
    String danielBody = "{\n" +
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
            "}";
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("userName eq \"daniel\""))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody(danielBody)));

    // All users query (no filter or empty filter)
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", absent())
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
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 0,\n" +
                "  \"Resources\": []\n" +
                "}")));

    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("id eq \"non-existing\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                    "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                    "  \"totalResults\": 0,\n" +
                    "  \"Resources\": []\n" +
                    "}")));

    // Multiple user IDs (OR query)
    String oscarAndMonsterBody = "{\n" +
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
            "}";
    
 // Multiple user IDs (OR query)
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
        .withQueryParam("filter", equalTo("(userName eq \"oscar\" or userName eq \"monster\")"))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody(oscarAndMonsterBody)));

    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", equalTo("(id eq \"user-oscar\" or id eq \"user-monster\")"))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody(oscarAndMonsterBody)));

    // Pagination sub-query 1
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", absent())
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", equalTo("2"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                        "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                        "  \"totalResults\": 3,\n" +
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
    
    // Pagination sub-query 2
    wireMockServer.stubFor(get(urlPathEqualTo("/Users"))
            .withQueryParam("filter", absent())
            .withQueryParam("startIndex", equalTo("3"))
            .withQueryParam("count", equalTo("2"))
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
    
    numberOfUsersCreated = 3;
  }

  protected void setupGroups() {
    // Group 1: development
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("displayName eq \"development\""))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"group-development\",\n" +
                "    \"externalId\": \"group-development\",\n" +
                "    \"displayName\": \"development\",\n" +
                "    \"members\": [\n" +
                "      {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "      {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
            .withQueryParam("filter", equalTo("externalId eq \"group-development\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                    "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                    "  \"totalResults\": 1,\n" +
                    "  \"Resources\": [{\n" +
                    "    \"id\": \"group-development\",\n" +
                    "    \"externalId\": \"group-development\",\n" +
                    "    \"displayName\": \"development\",\n" +
                    "    \"members\": [\n" +
                    "      {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                    "      {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                    "    ]\n" +
                    "  }]\n" +
                    "}")));

    // Group 2: management
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("displayName eq \"management\""))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 1,\n" +
                "  \"Resources\": [{\n" +
                "    \"id\": \"group-management\",\n" +
                "    \"externalId\": \"group-management\",\n" +
                "    \"displayName\": \"management\",\n" +
                "    \"members\": [\n" +
                "      {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
            .withQueryParam("filter", equalTo("externalId eq \"group-management\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                    "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                    "  \"totalResults\": 1,\n" +
                    "  \"Resources\": [{\n" +
                    "    \"id\": \"group-management\",\n" +
                    "    \"externalId\": \"group-management\",\n" +
                    "    \"displayName\": \"management\",\n" +
                    "    \"members\": [\n" +
                    "      {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                    "    ]\n" +
                    "  }]\n" +
                    "}")));


    // All groups query
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
            .withQueryParam("filter", equalTo("externalId eq \"non-existing\""))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                        "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                        "  \"totalResults\": 0,\n" +
                        "  \"Resources\": []\n" +
                        "}")));
 
    
    
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", absent())
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
                "        {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "        {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"group-management\",\n" +
                "      \"displayName\": \"management\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    // Groups by user filter - oscar
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("members[value eq \"user-oscar\"]"))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))
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
                "      {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "      {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "    ]\n" +
                "  }]\n" +
                "}")));

    // Groups by user filter - daniel (member of both)
    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
        .withQueryParam("filter", equalTo("members[value eq \"user-daniel\"]"))
        .withQueryParam("startIndex", equalTo("1"))
        .withQueryParam("count", matching(".*"))        
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                "  \"totalResults\": 2,\n" +
                "  \"Resources\": [\n" +
                "    {\n" +
                "      \"id\": \"group-development\",\n" +
                "      \"externalId\": \"group-development\",\n" +
                "      \"displayName\": \"development\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "        {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"group-management\",\n" +
                "      \"externalId\": \"group-management\",\n" +
                "      \"displayName\": \"management\",\n" +
                "      \"members\": [\n" +
                "        {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}")));

    wireMockServer.stubFor(get(urlPathEqualTo("/Groups"))
            .withQueryParam("filter", equalTo("members[value eq \"daniel\"]"))
            .withQueryParam("startIndex", equalTo("1"))
            .withQueryParam("count", matching(".*"))        
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\n" +
                    "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n" +
                    "  \"totalResults\": 2,\n" +
                    "  \"Resources\": [\n" +
                    "    {\n" +
                    "      \"id\": \"group-development\",\n" +
                    "      \"externalId\": \"group-development\",\n" +
                    "      \"displayName\": \"development\",\n" +
                    "      \"members\": [\n" +
                    "        {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                    "        {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"id\": \"group-management\",\n" +
                    "      \"externalId\": \"group-management\",\n" +
                    "      \"displayName\": \"management\",\n" +
                    "      \"members\": [\n" +
                    "        {\"value\": \"daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}")));

    // Get group by ID
    /*wireMockServer.stubFor(get(urlPathEqualTo("/Groups/development"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/scim+json")
            .withBody("{\n" +
                "  \"id\": \"group-development\",\n" +
                "  \"displayName\": \"development\",\n" +
                "  \"members\": [\n" +
                "    {\"value\": \"user-oscar\", \"$ref\": \"/Users/user-oscar\"},\n" +
                "    {\"value\": \"user-daniel\", \"$ref\": \"/Users/user-daniel\"}\n" +
                "  ]\n" +
                "}")));*/

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
