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

import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.integrationtest.functional.event.beans.TaskListenerProcessApplication;
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

import java.util.HashMap;
import java.util.Map;

/**
 * @author Daniel Meyer
 *
 */
@ExtendWith(ArquillianExtension.class)
public class ProcessApplicationTaskListenerTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive createDeployment() {
    WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
        .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
        .addAsLibraries(DeploymentHelper.getEngineCdi())
        .addAsResource("META-INF/processes.xml", "META-INF/processes.xml")
        .addClass(AbstractFoxPlatformIntegrationTest.class)
        .addClass(TaskListenerProcessApplication.class)
        .addAsResource("org/cibseven/bpm/integrationtest/functional/event/ProcessApplicationEventSupportTest.testTaskListener.bpmn20.xml");

    TestContainer.addContainerSpecificResourcesForNonPa(archive);

    return archive;

  }

  @Test
  public void testTaskListener() {
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put(TaskListener.EVENTNAME_CREATE, false);
    variables.put(TaskListener.EVENTNAME_ASSIGNMENT, false);
    variables.put(TaskListener.EVENTNAME_UPDATE, false);
    variables.put(TaskListener.EVENTNAME_COMPLETE, false);

    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", variables);

    boolean createEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_CREATE);
    boolean assignmentEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_ASSIGNMENT);
    boolean updateEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_UPDATE);
    boolean completeEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_COMPLETE);

    Assertions.assertTrue(createEventFired);
    Assertions.assertFalse(assignmentEventFired);
    Assertions.assertFalse(completeEventFired);
    Assertions.assertFalse(updateEventFired);

    Task task = taskService.createTaskQuery().processDefinitionKey("testProcess").singleResult();
    taskService.claim(task.getId(), "jonny");

    createEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_CREATE);
    assignmentEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_ASSIGNMENT);
    updateEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_UPDATE);
    completeEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_COMPLETE);

    Assertions.assertTrue(createEventFired);
    Assertions.assertTrue(assignmentEventFired);
    Assertions.assertTrue(updateEventFired);
    Assertions.assertFalse(completeEventFired);

    taskService.complete(task.getId());

    createEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_CREATE);
    assignmentEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_ASSIGNMENT);
    updateEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_UPDATE);
    completeEventFired = (Boolean) runtimeService.getVariable(processInstance.getId(), TaskListener.EVENTNAME_COMPLETE);

    Assertions.assertTrue(createEventFired);
    Assertions.assertTrue(assignmentEventFired);
    Assertions.assertTrue(updateEventFired);
    Assertions.assertTrue(completeEventFired);
  }
}