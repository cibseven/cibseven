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
package org.cibseven.bpm.rest;

import org.cibseven.bpm.rest.beans.CustomProcessEngineProvider;
import org.cibseven.bpm.rest.beans.CustomRestApplication;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.fail;
import org.jboss.arquillian.junit5.ArquillianExtension;


import java.io.IOException;

@ExtendWith(ArquillianExtension.class)
public class EmbeddedEngineRest_WILDFLY {

  private static final String EMBEDDED_ENGINE_REST = "embedded-engine-rest";

  @ArquillianResource
  private Deployer deployer;

  @Deployment(managed=false, name = EMBEDDED_ENGINE_REST)
  public static WebArchive createDeployment() {
    JavaArchive[] engineRestClasses = getEngineRestClasses();
    WebArchive archive = ShrinkWrap.create(WebArchive.class, "embedded-engine-rest.war")
        .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
        .addAsWebInfResource("jboss-deployment-structure.xml")
        .addAsManifestResource("org.cibseven.bpm.engine.rest.spi.ProcessEngineProvider", "META-INF/services/org.cibseven.bpm.engine.rest.spi.ProcessEngineProvider")
        .addAsLibraries(engineRestClasses)
        .addClasses(CustomRestApplication.class, CustomProcessEngineProvider.class);

    return archive;
  }

  @Test
  @RunAsClient
  public void testDeploymentWorks() throws IOException {
    try {
      deployer.deploy(EMBEDDED_ENGINE_REST);
      deployer.undeploy(EMBEDDED_ENGINE_REST);
    } catch(Exception e) {
      fail("Embedded engine-rest deployment failed because of " + e);
    }
  }

  private static JavaArchive[] getEngineRestClasses() {
    String coordinates = "org.cibseven.bpm:cibseven-engine-rest:jar:classes:" + System.getProperty("projectversion");

    JavaArchive[] resolvedArchives = Maven.configureResolver()
          .workOffline()
          .loadPomFromFile("pom.xml")
          .resolve(coordinates)
          .withTransitivity()
          .as(JavaArchive.class);

      if(resolvedArchives.length < 1) {
        throw new RuntimeException("Could not resolve " + coordinates);
      } else {
        return resolvedArchives;
      }
  }
}
