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
package org.cibseven.bpm.engine.test.api.externaltask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Thorben Lindhauer
 *
 */
public class ExternalTaskSupportTest {

  @RegisterExtension
  public ProcessEngineRule rule = new ProvidedProcessEngineRule();

  public static Collection<Object[]> processResources() {
    return Arrays.asList(new Object[][] {
      {"org/cibseven/bpm/engine/test/api/externaltask/ExternalTaskSupportTest.businessRuleTask.bpmn20.xml"},
      {"org/cibseven/bpm/engine/test/api/externaltask/ExternalTaskSupportTest.messageEndEvent.bpmn20.xml"},
      {"org/cibseven/bpm/engine/test/api/externaltask/ExternalTaskSupportTest.messageIntermediateEvent.bpmn20.xml"},
      {"org/cibseven/bpm/engine/test/api/externaltask/ExternalTaskSupportTest.sendTask.bpmn20.xml"}
    });
  }

  protected String deploymentId;

  protected void setUp(String processDefinitionResource) {
    deploymentId = rule.getRepositoryService()
        .createDeployment()
        .addClasspathResource(processDefinitionResource)
        .deploy()
        .getId();
  }

  @AfterEach
  public void tearDown() {
    if (deploymentId != null) {
      rule.getRepositoryService().deleteDeployment(deploymentId, true);
    }
  }

  @ParameterizedTest
  @MethodSource("processResources")
  public void testExternalTaskSupport(String processDefinitionResource) {
    // given
    setUp(processDefinitionResource);
    ProcessDefinition processDefinition = rule.getRepositoryService().createProcessDefinitionQuery().singleResult();

    // when
    ProcessInstance processInstance = rule.getRuntimeService().startProcessInstanceById(processDefinition.getId());

    // then
    List<LockedExternalTask> externalTasks = rule
        .getExternalTaskService()
        .fetchAndLock(1, "aWorker")
        .topic("externalTaskTopic", 5000L)
        .execute();

    Assertions.assertEquals(1, externalTasks.size());
    Assertions.assertEquals(processInstance.getId(), externalTasks.get(0).getProcessInstanceId());

    // and it is possible to complete the external task successfully and end the process instance
    rule.getExternalTaskService().complete(externalTasks.get(0).getId(), "aWorker");

    Assertions.assertEquals(0L, rule.getRuntimeService().createProcessInstanceQuery().count());
  }

  @ParameterizedTest
  @MethodSource("processResources")
  public void testExternalTaskProperties(String processDefinitionResource) {
    // given
    setUp(processDefinitionResource);
    ProcessDefinition processDefinition = rule.getRepositoryService().createProcessDefinitionQuery().singleResult();
    rule.getRuntimeService().startProcessInstanceById(processDefinition.getId());

    // when
    List<LockedExternalTask> externalTasks = rule
        .getExternalTaskService()
        .fetchAndLock(1, "aWorker")
        .topic("externalTaskTopic", 5000L)
        .includeExtensionProperties()
        .execute();

    // then
    LockedExternalTask task = externalTasks.get(0);
    Map<String, String> properties = task.getExtensionProperties();
    assertThat(properties).containsOnly(
        entry("key1", "val1"),
        entry("key2", "val2"));
  }
}
