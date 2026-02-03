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
package org.cibseven.bpm.integrationtest.functional.delegation;

import org.cibseven.bpm.integrationtest.functional.delegation.beans.DelegateVarMapping;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import static org.assertj.core.api.Assertions.assertThat;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.task.TaskQuery;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestConstants;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ExtendWith(ArquillianExtension.class)
public class DelegatedVariableMappingTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment(name = "mainDeployment")
  public static WebArchive createProcessArchiveDeplyoment() {
    WebArchive webArchive = ShrinkWrap.create(WebArchive.class, "mainDeployment.war")
        .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
        .addAsLibraries(DeploymentHelper.getEngineCdi())
        .addAsResource("META-INF/processes.xml", "META-INF/processes.xml")
        .addClass(AbstractFoxPlatformIntegrationTest.class)
        .addClass(TestConstants.class)
        .addClass(DelegateVarMapping.class)
        .addAsResource("org/cibseven/bpm/integrationtest/functional/delegation/DelegatedVariableMappingTest.testCallSubProcessWithDelegatedVariableMapping.bpmn20.xml")
        .addAsResource("org/cibseven/bpm/integrationtest/functional/delegation/DelegatedVariableMappingTest.testCallSubProcessWithDelegatedVariableMappingExpression.bpmn20.xml");

    TestContainer.addContainerSpecificResourcesEmbedCdiLib(webArchive);

    return webArchive;
  }

  @Deployment(name = "calledDeployment")
  public static WebArchive createSecondProcessArchiveDeployment() {
    WebArchive webArchive = ShrinkWrap.create(WebArchive.class, "calledDeployment.war")
        .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
        .addAsLibraries(DeploymentHelper.getEngineCdi())
        .addAsResource("META-INF/processes.xml", "META-INF/processes.xml")
        .addClass(AbstractFoxPlatformIntegrationTest.class)
        .addClass(TestConstants.class)
        .addAsResource("org/cibseven/bpm/integrationtest/functional/delegation/simpleSubProcess.bpmn20.xml");

    TestContainer.addContainerSpecificResourcesEmbedCdiLib(webArchive);

    return webArchive;
  }

  @Inject
  private BeanManager beanManager;

  protected void testDelegation() {
    TaskQuery taskQuery = taskService.createTaskQuery();

    //when
    Task taskInSubProcess = taskQuery.singleResult();
    assertThat(taskInSubProcess.getName()).isEqualTo("Task in subprocess");

    //then check value from input variable
    Object inputVar = runtimeService.getVariable(taskInSubProcess.getProcessInstanceId(), "TestInputVar");
    assertThat(inputVar).isEqualTo("inValue");

    //when completing the task in the subprocess, finishes the subprocess
    taskService.complete(taskInSubProcess.getId());
    Task taskAfterSubProcess = taskQuery.singleResult();
    assertThat(taskAfterSubProcess.getName()).isEqualTo("Task after subprocess");

    //then check value from output variable
    ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().singleResult();
    Object outputVar = runtimeService.getVariable(processInstance.getId(), "TestOutputVar");
    assertThat(outputVar).isEqualTo("outValue");

    //complete task after sub process
    taskService.complete(taskAfterSubProcess.getId());
  }

  @Test
  @OperateOnDeployment("mainDeployment")
  public void testDelegateVariableMapping() {
    //given
    runtimeService.startProcessInstanceByKey("callSimpleSubProcess");
    testDelegation();
  }

  @Test
  @OperateOnDeployment("mainDeployment")
  public void testDelegateVariableMappingExpression() {
    runtimeService.startProcessInstanceByKey("callSubProcessExpr");
    testDelegation();
  }

}