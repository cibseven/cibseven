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
package org.cibseven.bpm.integrationtest.functional.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.integrationtest.functional.event.beans.ExecutionListenerProcessApplication;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Daniel Meyer
 *
 */
@ExtendWith(ArquillianExtension.class)
public class ProcessApplicationExecutionListenerTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive createDeployment() {
    WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
      .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
      .addAsLibraries(DeploymentHelper.getEngineCdi())
      .addAsResource("META-INF/processes.xml", "META-INF/processes.xml")
      .addClass(AbstractFoxPlatformIntegrationTest.class)
      .addClass(ExecutionListenerProcessApplication.class)
      .addAsResource("org/cibseven/bpm/integrationtest/functional/event/ProcessApplicationEventSupportTest.testExecutionListener.bpmn20.xml");

    TestContainer.addContainerSpecificResourcesForNonPa(archive);

    return archive;

  }

  @Test
  public void testExecutionListener() {
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess");

    Integer listenerInvocationCount = (Integer) runtimeService.getVariable(processInstance.getId(), ExecutionListenerProcessApplication.LISTENER_INVOCATION_COUNT);
    Assertions.assertNotNull(listenerInvocationCount);
    assertThat(listenerInvocationCount.intValue()).isEqualTo(5);
  }

}