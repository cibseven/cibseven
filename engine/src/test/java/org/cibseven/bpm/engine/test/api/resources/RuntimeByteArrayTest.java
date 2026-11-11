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
package org.cibseven.bpm.engine.test.api.resources;

import static org.cibseven.bpm.engine.repository.ResourceTypes.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.ibatis.jdbc.RuntimeSqlException;
import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.impl.batch.BatchEntity;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.VariableInstanceEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.FailingDelegate;
import org.cibseven.bpm.engine.test.api.runtime.migration.MigrationTestRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.batch.BatchMigrationHelper;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.FileValue;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;


public class RuntimeByteArrayTest {
  protected static final String WORKER_ID = "aWorkerId";
  protected static final long LOCK_TIME = 10000L;
  protected static final String TOPIC_NAME = "externalTaskTopic";

  @RegisterExtension
  @Order(4) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);
  @RegisterExtension
  protected MigrationTestRule migrationRule = new MigrationTestRule(engineRule);
  protected BatchMigrationHelper helper = new BatchMigrationHelper(engineRule, migrationRule);


  protected ProcessEngineConfigurationImpl configuration;
  protected RuntimeService runtimeService;
  protected ManagementService managementService;
  protected TaskService taskService;
  protected RepositoryService repositoryService;
  protected ExternalTaskService externalTaskService;

  protected String id;

  @BeforeEach
  public void initServices() {
    configuration = engineRule.getProcessEngineConfiguration();
    runtimeService = engineRule.getRuntimeService();
    managementService = engineRule.getManagementService();
    taskService = engineRule.getTaskService();
    repositoryService = engineRule.getRepositoryService();
    externalTaskService = engineRule.getExternalTaskService();
  }

  @AfterEach
  public void removeBatches() {
    helper.removeAllRunningAndHistoricBatches();
  }

  @AfterEach
  public void tearDown() {
    if (id != null) {
      // delete task
      taskService.deleteTask(id, true);
    }
    ClockUtil.setCurrentTime(new Date());
  }

  @Test
  public void testVariableBinaryForFileValues() {
    // given
    BpmnModelInstance instance = createProcess();

    testRule.deploy(instance);
    FileValue fileValue = createFile();

    runtimeService.startProcessInstanceByKey("Process", Variables.createVariables().putValueTyped("fileVar", fileValue));

    String byteArrayValueId = ((VariableInstanceEntity)runtimeService.createVariableInstanceQuery().singleResult()).getByteArrayValueId();

    // when
    ByteArrayEntity byteArrayEntity = configuration.getCommandExecutorTxRequired()
        .execute(new GetByteArrayCommand(byteArrayValueId));

    checkBinary(byteArrayEntity);
  }

  @Test
  public void testVariableBinary() {
    byte[] binaryContent = "some binary content".getBytes();

    // given
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("binaryVariable", binaryContent);
    Task task = taskService.newTask();
    taskService.saveTask(task);
    id = task.getId();
    taskService.setVariablesLocal(id, variables);

    String byteArrayValueId = ((VariableInstanceEntity)runtimeService.createVariableInstanceQuery().singleResult()).getByteArrayValueId();

    // when
    ByteArrayEntity byteArrayEntity = configuration.getCommandExecutorTxRequired()
        .execute(new GetByteArrayCommand(byteArrayValueId));

    checkBinary(byteArrayEntity);
  }

  @Test
  public void testBatchBinary() {
    // when
    helper.migrateProcessInstancesAsync(15);

    String byteArrayValueId = ((BatchEntity) managementService.createBatchQuery().singleResult()).getConfiguration();

    ByteArrayEntity byteArrayEntity = configuration.getCommandExecutorTxRequired()
        .execute(new GetByteArrayCommand(byteArrayValueId));

    checkBinary(byteArrayEntity);
  }

  @Test
  public void testExceptionStacktraceBinary() {
    // given
    BpmnModelInstance instance = createFailingProcess();
    testRule.deploy(instance);
    runtimeService.startProcessInstanceByKey("Process");
    String jobId = managementService.createJobQuery().singleResult().getId();

    // when
    try {
      managementService.executeJob(jobId);
      fail();
    } catch (Exception e) {
      // expected
    }

    JobEntity job = (JobEntity) managementService.createJobQuery().singleResult();
    assertNotNull(job);

    ByteArrayEntity byteArrayEntity = configuration.getCommandExecutorTxRequired().execute(new GetByteArrayCommand(job.getExceptionByteArrayId()));

    checkBinary(byteArrayEntity);
  }

  @Test
  public void testExternalTaskStacktraceBinary() {
    // given
    testRule.deploy("org/cibseven/bpm/engine/test/api/externaltask/oneExternalTaskProcess.bpmn20.xml");
    runtimeService.startProcessInstanceByKey("oneExternalTaskProcess");

    List<LockedExternalTask> tasks = externalTaskService.fetchAndLock(5, WORKER_ID)
        .topic(TOPIC_NAME, LOCK_TIME)
        .execute();

    LockedExternalTask task = tasks.get(0);

    // submitting a failure (after a simulated processing time of three seconds)
    ClockUtil.setCurrentTime(nowPlus(3000L));

    String errorMessage;
    String exceptionStackTrace;
    try {
      throw new RuntimeSqlException("test cause");
    } catch (RuntimeException e) {
      exceptionStackTrace = ExceptionUtils.getStackTrace(e);
      errorMessage = e.getMessage();
    }
    assertNotNull(exceptionStackTrace);

    externalTaskService.handleFailure(task.getId(), WORKER_ID, errorMessage, exceptionStackTrace, 5, 3000L);

    ExternalTaskEntity externalTask = (ExternalTaskEntity) externalTaskService.createExternalTaskQuery().singleResult();

    ByteArrayEntity byteArrayEntity = configuration.getCommandExecutorTxRequired().execute(new GetByteArrayCommand(externalTask.getErrorDetailsByteArrayId()));

    // then
    checkBinary(byteArrayEntity);
  }

  protected void checkBinary(ByteArrayEntity byteArrayEntity) {
    assertNotNull(byteArrayEntity);
    assertNotNull(byteArrayEntity.getCreateTime());
    assertEquals(RUNTIME.getValue(), byteArrayEntity.getType());
  }

  protected FileValue createFile() {
    String fileName = "text.txt";
    String encoding = "crazy-encoding";
    String mimeType = "martini/dry";

    FileValue fileValue = Variables
        .fileValue(fileName)
        .file("ABC".getBytes())
        .encoding(encoding)
        .mimeType(mimeType)
        .create();
    return fileValue;
  }

  protected BpmnModelInstance createProcess() {
    return Bpmn.createExecutableProcess("Process")
      .startEvent()
      .userTask("user")
      .endEvent()
      .done();
  }

  protected BpmnModelInstance createFailingProcess() {
    return Bpmn.createExecutableProcess("Process")
      .startEvent()
      .serviceTask("failing")
      .camundaAsyncAfter()
      .camundaAsyncBefore()
      .camundaClass(FailingDelegate.class)
      .endEvent()
      .done();
  }

  protected Date nowPlus(long millis) {
    return new Date(ClockUtil.getCurrentTime().getTime() + millis);
  }

}
