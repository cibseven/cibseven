/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.test.standalone.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.cibseven.bpm.engine.impl.test.RequiredDatabase;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.ProcessEngineTestCase;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Uses {@link ProcessEngineTestCase} as JUnit 5 base class, {@link ProcessEngineTestCaseTest}
 * covers the usage as extension.
 */
public class ProcessEngineTestCaseBaseClassTest extends ProcessEngineTestCase {

  protected static final String SIMPLE_PROCESS =
      "org/cibseven/bpm/engine/test/standalone/testing/ProcessEngineTestCaseTest.testSimpleProcess.bpmn20.xml";

  protected String deploymentIdInSubclassSetUp;

  /**
   * Runs after the {@code @BeforeEach} method of the base class: the process engine and the
   * services are initialized and the deployment of the test method is already done.
   */
  @BeforeEach
  public void setUpSubclass() {
    assertThat(processEngine).isNotNull();
    assertThat(repositoryService).isNotNull();
    assertThat(runtimeService).isNotNull();
    assertThat(taskService).isNotNull();
    assertThat(historyService).isNotNull();
    assertThat(managementService).isNotNull();
    deploymentIdInSubclassSetUp = getDeploymentId();
  }

  @Test
  @Deployment(resources = SIMPLE_PROCESS)
  public void testSimpleProcess() {
    assertThat(deploymentIdInSubclassSetUp).isNotNull().isEqualTo(getDeploymentId());
    assertEquals(1, repositoryService.createDeploymentQuery().deploymentId(getDeploymentId()).count());

    runtimeService.startProcessInstanceByKey("simpleProcess");

    Task task = taskService.createTaskQuery().singleResult();
    assertEquals("My Task", task.getName());

    taskService.complete(task.getId());
    assertEquals(0, runtimeService.createProcessInstanceQuery().count());
  }

  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void testRequiredHistoryLevelFull() {
    assertThat(isSkipTest()).isFalse();
    assertThat(processEngine.getProcessEngineConfiguration().getHistory())
        .isEqualTo(ProcessEngineConfiguration.HISTORY_FULL);
  }

  /** Skipped on H2, fails there if the base class ignores {@link RequiredDatabase}. */
  @Test
  @RequiredDatabase(excludes = DbSqlSessionFactory.H2)
  public void testRequiredDatabaseExcludesH2() {
    assertThat(isSkipTest()).isFalse();
    assertThat(processEngine.getProcessEngineConfiguration().getDatabaseType())
        .isNotEqualTo(DbSqlSessionFactory.H2);
  }

}
