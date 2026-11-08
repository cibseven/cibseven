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
package org.cibseven.bpm.spring.boot.starter.rest;

import jakarta.ws.rs.ApplicationPath;
import org.cibseven.bpm.engine.rest.impl.CamundaRestResources;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.springframework.beans.factory.InitializingBean;

/**
 * The Jersey application of the REST API.
 *
 * <p>JSON is written and read by the Jackson 3 provider of the REST API
 * (CibsevenJacksonJsonProvider with the mapper of the JacksonConfigurator), which is part of
 * {@link CamundaRestResources#getConfigurationClasses()}. The JSON features of Jersey are switched
 * off (property {@value #JERSEY_SERVER_JSON_FEATURE}): Jersey's JacksonFeature
 * (jersey-media-json-jackson) is Jackson 2 and, if the application brings it back, would take over
 * with a different mapper (other date format, other error responses).
 */
@ApplicationPath("/engine-rest")
public class CamundaJerseyResourceConfig extends ResourceConfig implements InitializingBean {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(CamundaJerseyResourceConfig.class);

  /**
   * Jersey property naming the one JSON feature that may configure itself
   * (org.glassfish.jersey.internal.InternalProperties#JSON_FEATURE_SERVER). Jersey's JSON features
   * (Jackson, JSON-B, MOXy, ...) do nothing if it names another feature.
   */
  public static final String JERSEY_SERVER_JSON_FEATURE = "jersey.config.server.jsonFeature";

  /**
   * Value of {@link #JERSEY_SERVER_JSON_FEATURE}: the JSON provider of the REST API.
   */
  public static final String JSON_FEATURE = "CibsevenJacksonJsonProvider";

  @Override
  public void afterPropertiesSet() throws Exception {
    registerCamundaRestResources();
    registerAdditionalResources();
  }

  protected void registerCamundaRestResources() {
    log.info("Configuring camunda rest api.");

    this.registerClasses(CamundaRestResources.getResourceClasses());
    this.registerClasses(CamundaRestResources.getConfigurationClasses());
    this.property(JERSEY_SERVER_JSON_FEATURE, JSON_FEATURE);

    log.info("Finished configuring camunda rest api.");
  }

  protected void registerAdditionalResources() {

  }

}
