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
package org.cibseven.bpm.integrationtest.functional.cdi;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.integrationtest.functional.cdi.beans.DependentScopedBean;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * @author Tassilo Weidner
 */
@RunWith(Arquillian.class)
public class CdiBeanDependentScopedAsyncTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive processArchive() {
    return initWebArchiveDeployment()
      .addClass(DependentScopedBean.class)
      .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiBeanDependentScoped.testResolveBeanFromJobExecutor.bpmn20.xml");
  }

  @Deployment(name="clientDeployment")
  public static WebArchive clientDeployment() {
    WebArchive deployment = ShrinkWrap.create(WebArchive.class, "client.war")
            .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
            .addClass(AbstractFoxPlatformIntegrationTest.class)
            .addClass(DependentScopedBean.class)
            .addAsLibraries(DeploymentHelper.getEngineCdi());

    TestContainer.addContainerSpecificResourcesForNonPaEmbedCdiLib(deployment);

    return deployment;
  }

  @BeforeEach
  @OperateOnDeployment("clientDeployment")
  void setup() {
    DependentScopedBean.reset();

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(0);

    runtimeService.startProcessInstanceByKey("testResolveBeanFromJobExecutor");

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(1);

    waitForJobExecutorToProcessAllJobs();

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(0);
  }

  @Test
  void resolveBeanFromJobExecutor() {
    assertThat(DependentScopedBean.lifecycle).isEqualTo(Arrays.asList("post-construct-invoked", "bean-invoked", "pre-destroy-invoked"));
  }

}
