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
package org.cibseven.bpm.qa.upgrade;


import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.qa.upgrade.scenarios.boundary.NonInterruptingBoundaryEventScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.InterruptingEventSubProcessCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.InterruptingEventSubProcessNestedCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.NestedCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.NestedMultiInstanceCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.NonInterruptingEventSubProcessCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.ParallelMultiInstanceCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.SequentialMultiInstanceCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.SingleActivityCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.compensation.SingleActivityConcurrentCompensationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.job.JobMigrationScenario;
import org.cibseven.bpm.qa.upgrade.scenarios.sentry.SentryScenario;

/**
 * Sets up scenarios for migration from 7.3.0
 *
 * @author Thorben Lindhauer
 */
public class TestFixture {

  public static final String ENGINE_VERSION = "7.3.0";

  protected ProcessEngine processEngine;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected ManagementService managementService;
  protected TaskService taskService;

  public TestFixture(ProcessEngine processEngine) {
    this.processEngine = processEngine;
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    managementService = processEngine.getManagementService();
    taskService = processEngine.getTaskService();
  }

  public static void main(String[] args) {
    ProcessEngineConfigurationImpl processEngineConfiguration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
      .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    ProcessEngine processEngine = processEngineConfiguration.buildProcessEngine();

    // register test scenarios
    ScenarioRunner runner = new ScenarioRunner(processEngine, ENGINE_VERSION);

    // cmmn sentries
    runner.setupScenarios(SentryScenario.class);

    // compensation
    runner.setupScenarios(SingleActivityCompensationScenario.class);
    runner.setupScenarios(NestedCompensationScenario.class);
    runner.setupScenarios(SingleActivityConcurrentCompensationScenario.class);
    runner.setupScenarios(ParallelMultiInstanceCompensationScenario.class);
    runner.setupScenarios(SequentialMultiInstanceCompensationScenario.class);
    runner.setupScenarios(NestedMultiInstanceCompensationScenario.class);
    runner.setupScenarios(InterruptingEventSubProcessCompensationScenario.class);
    runner.setupScenarios(NonInterruptingEventSubProcessCompensationScenario.class);
    runner.setupScenarios(InterruptingEventSubProcessNestedCompensationScenario.class);

    // job
    runner.setupScenarios(JobMigrationScenario.class);

    // boundary events
    runner.setupScenarios(NonInterruptingBoundaryEventScenario.class);

    processEngine.close();
  }
}
