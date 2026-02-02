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
package org.cibseven.bpm.integrationtest.functional.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Map;

import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class CatchErrorFromProcessApplicationTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive createProcessArchiveDeployment() {
    return initWebArchiveDeployment()
      .addClass(ThrowErrorDelegate.class)
      .addClass(MyBusinessException.class)
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.delegateExpression.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.sequentialMultiInstance.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.delegateExpression.sequentialMultiInstance.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.parallelMultiInstance.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/error/CatchErrorFromProcessApplicationTest.delegateExpression.parallelMultiInstance.bpmn20.xml");
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

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInExecute() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInExecute() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInSignal() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInSignal() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInExecuteSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInExecuteSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInSignalSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    // signal 2 times to execute first sequential behaviors
    runtimeService.setVariables(pi, leaveExecution());
    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    runtimeService.setVariables(pi, leaveExecution());

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInSignalSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    // signal 2 times to execute first sequential behaviors
    runtimeService.setVariables(pi, leaveExecution());
    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    runtimeService.setVariables(pi, leaveExecution());

    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInExecuteParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInExecuteParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInSignalParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").list().get(3);
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInSignalParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").list().get(3);
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionExecute() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionExecute() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionSignal() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionSignal() {
    String pi = runtimeService.startProcessInstanceByKey("testProcess").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionExecuteSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionExecuteSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionSignalSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    // signal 2 times to execute first sequential behaviors
    runtimeService.setVariables(pi, leaveExecution());
    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    runtimeService.setVariables(pi, leaveExecution());

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionSignalSequentialMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessSequentialMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    // signal 2 times to execute first sequential behaviors
    runtimeService.setVariables(pi, leaveExecution());
    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    runtimeService.setVariables(pi, leaveExecution());

    runtimeService.signal(runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult().getId());
    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").singleResult();
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionExecuteParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI", throwException()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionExecuteParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI", throwError()).getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwExceptionInDelegateExpressionSignalParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").list().get(3);
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwException());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskException");

    taskService.complete(userTask.getId());
  }

  @Test
  @OperateOnDeployment("clientDeployment")
  void throwErrorInDelegateExpressionSignalParallelMultiInstance() {
    String pi = runtimeService.startProcessInstanceByKey("testProcessParallelMI").getId();

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat(runtimeService.getVariable(pi, "signaled")).isNull();

    Execution serviceTask = runtimeService.createExecutionQuery().processInstanceId(pi).activityId("serviceTask").list().get(3);
    assertThat(serviceTask).isNotNull();

    runtimeService.setVariables(pi, throwError());
    runtimeService.signal(serviceTask.getId());

    assertThat((Boolean) runtimeService.getVariable(pi, "executed")).isTrue();
    assertThat((Boolean) runtimeService.getVariable(pi, "signaled")).isTrue();

    Task userTask = taskService.createTaskQuery().processInstanceId(pi).singleResult();
    assertThat(userTask).isNotNull();
    assertThat(userTask.getTaskDefinitionKey()).isEqualTo("userTaskError");

    taskService.complete(userTask.getId());
  }

  public Map<String, Object> throwError() {
    return Collections.singletonMap("type", (Object) "error");
  }

  public Map<String, Object> throwException() {
    return Collections.singletonMap("type", (Object) "exception");
  }

  public Map<String, Object> leaveExecution() {
    return Collections.singletonMap("type", (Object) "leave");
  }

}
