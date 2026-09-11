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
import org.cibseven.bpm.engine.cdi.impl.util.BeanManagerLookup;
import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.cibseven.bpm.integrationtest.functional.cdi.beans.ExampleDelegateBean;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.fail;

/**
 * <p>Deploys two different applications, a process archive and a cleint application.</p>
 *
 * <p>This test ensures that when the process is started from the client,
 * it is able to make the context switch to the process archvie and resolve cdi beans
 * from the process archive.</p>
 *
 *
 * @author Daniel Meyer
 */
@ExtendWith(ArquillianExtension.class)
public class CdiDelegateBeanResolutionTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive processArchive() {
    return initWebArchiveDeployment()
            .addClass(ExampleDelegateBean.class)
            .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiDelegateBeanResolutionTest.testResolveBean.bpmn20.xml")
            .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiDelegateBeanResolutionTest.testResolveBeanFromJobExecutor.bpmn20.xml");
  }

  @Deployment(name="clientDeployment")
  public static WebArchive clientDeployment() {
     WebArchive webArchive = ShrinkWrap.create(WebArchive.class, "client.war")
            .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
            .addClass(ProgrammaticBeanLookup.class)
            .addClass(BeanManagerLookup.class)
            .addClass(AbstractFoxPlatformIntegrationTest.class)
            .addAsLibraries(DeploymentHelper.getEngineCdi());

     TestContainer.addContainerSpecificResourcesEmbedCdiLib(webArchive);

     return webArchive;
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  public void testResolveBean() {
    try {
      // assert that we cannot resolve the bean here:
      ProgrammaticBeanLookup.lookup("exampleDelegateBean");
      fail("exception expected");
    }catch (Throwable e) {
      // expected
    }

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBean").count()).isEqualTo(0);
    // but the process engine can:
    runtimeService.startProcessInstanceByKey("testResolveBean");

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBean").count()).isEqualTo(0);
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  public void testResolveBeanFromJobExecutor() {

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(0);
    runtimeService.startProcessInstanceByKey("testResolveBeanFromJobExecutor");
    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(1);

    waitForJobExecutorToProcessAllJobs();

    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("testResolveBeanFromJobExecutor").count()).isEqualTo(0);

  }

}
