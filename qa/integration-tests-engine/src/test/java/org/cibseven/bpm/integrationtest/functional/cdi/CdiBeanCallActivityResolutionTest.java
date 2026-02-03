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

import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.integrationtest.functional.cdi.beans.ConditionalFlowBean;
import org.cibseven.bpm.integrationtest.functional.cdi.beans.ProcessVariableBean;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;

/**
 * @author Thorben Lindhauer
 *
 */
@RunWith(Arquillian.class)
public class CdiBeanCallActivityResolutionTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment(name="pa1")
  public static WebArchive createCallingProcessDeployment() {
    return initWebArchiveDeployment("pa1.war")
            .addClass(ConditionalFlowBean.class)
            .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiBeanCallActivityResolutionTest.callingProcess.bpmn20.xml")
            .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiBeanCallActivityResolutionTest.callingProcessConditionalFlow.bpmn20.xml");

  }

  @Deployment(name="pa2")
  public static WebArchive createCalledProcessDeployment() {
    return initWebArchiveDeployment("pa2.war")
            .addClass(ProcessVariableBean.class)
            .addAsResource("org/cibseven/bpm/integrationtest/functional/cdi/CdiBeanCallActivityResolutionTest.calledProcess.bpmn20.xml");
  }

  @Deployment(name="clientDeployment")
  public static WebArchive clientDeployment() {
    WebArchive deployment = ShrinkWrap.create(WebArchive.class, "client.war")
            .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
            .addClass(AbstractFoxPlatformIntegrationTest.class)
            .addAsLibraries(DeploymentHelper.getEngineCdi());

    TestContainer.addContainerSpecificResourcesForNonPa(deployment);

    return deployment;
  }

  protected ProcessInstance processInstance;

  @AfterEach
  void tearDown() {
    if (processInstance != null) {
      runtimeService.deleteProcessInstance(processInstance.getId(), null);
    }
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void resolveBeanInBpmnProcess() {
    processInstance = runtimeService.startProcessInstanceByKey("callingProcess");

    Task calledProcessTask = taskService.createTaskQuery().singleResult();

    taskService.complete(calledProcessTask.getId(),
        Variables.createVariables().putValue("var", "value"));

    Task afterCallActivityTask = taskService.createTaskQuery().singleResult();
    assertThat(afterCallActivityTask).isNotNull();
    assertThat(afterCallActivityTask.getTaskDefinitionKey()).isEqualTo("afterCallActivity");

    String variable = (String) runtimeService.getVariable(processInstance.getId(), "var");
    assertThat(variable).isEqualTo("valuevalue");
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void resolveBeanInBpmnProcessConditionalFlow() {
    // given
    processInstance = runtimeService.startProcessInstanceByKey("callingProcessConditionalFlow",
        Variables.createVariables().putValue("takeFlow", true));

    Task calledProcessTask = taskService.createTaskQuery().singleResult();

    // when
    taskService.complete(calledProcessTask.getId());

    // then
    Task afterCallActivityTask = taskService.createTaskQuery().singleResult();
    assertThat(afterCallActivityTask).isNotNull();
    assertThat(afterCallActivityTask.getTaskDefinitionKey()).isEqualTo("afterCallActivityTask");
  }

}
