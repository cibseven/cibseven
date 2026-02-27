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
package org.cibseven.bpm.engine.test.jobexecutor;

import java.util.List;

import org.cibseven.bpm.engine.impl.Page;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AcquirableJobEntity;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.api.runtime.migration.models.ProcessModels;
import org.cibseven.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.Assume;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;

import org.junit.jupiter.api.Test;


public class DeploymentAwareJobExecutorForOracleTest {

  @RegisterExtension
  @Order(3) public static ProcessEngineBootstrapRule deploymentAwareBootstrapRule = new ProcessEngineBootstrapRule(configuration ->
      configuration.setJobExecutorDeploymentAware(true));
  @RegisterExtension
  @Order(4) protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule(deploymentAwareBootstrapRule);
  @RegisterExtension
  @Order(9) protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Test
  public void testFindAcquirableJobsWhen0InstancesDeployed() {
    // given
    Assume.assumeTrue(engineRule.getProcessEngineConfiguration().getDatabaseType().equals("oracle"));

    // then
    findAcquirableJobs();
  }

  @Test
  public void testFindAcquirableJobsWhen1InstanceDeployed() {
    // given
    Assume.assumeTrue(engineRule.getProcessEngineConfiguration().getDatabaseType().equals("oracle"));
    // when
    testRule.deploy(ProcessModels.ONE_TASK_PROCESS);
    // then
    findAcquirableJobs();
  }

  @Test
  public void testFindAcquirableJobsWhen1000InstancesDeployed() {
    // given
    Assume.assumeTrue(engineRule.getProcessEngineConfiguration().getDatabaseType().equals("oracle"));
    // when
    for (int i=0; i<1000; i++) {
      testRule.deploy(ProcessModels.ONE_TASK_PROCESS);
    }
    // then
    findAcquirableJobs();
  }

  @Test
  public void testFindAcquirableJobsWhen1001InstancesDeployed() {
    // given
    Assume.assumeTrue(engineRule.getProcessEngineConfiguration().getDatabaseType().equals("oracle"));
    // when
    for (int i=0; i<1001; i++) {
      testRule.deploy(ProcessModels.ONE_TASK_PROCESS);
    }
    // then
    findAcquirableJobs();
  }

  @Test
  public void testFindAcquirableJobsWhen2000InstancesDeployed() {
    // given
    Assume.assumeTrue(engineRule.getProcessEngineConfiguration().getDatabaseType().equals("oracle"));
    // when
    for (int i=0; i<2000; i++) {
      testRule.deploy(ProcessModels.ONE_TASK_PROCESS);
    }
    // then
    findAcquirableJobs();
  }

  protected List<AcquirableJobEntity> findAcquirableJobs() {
    return engineRule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<List<AcquirableJobEntity>>() {

      @Override
      public List<AcquirableJobEntity> execute(CommandContext commandContext) {
        return commandContext
          .getJobManager()
          .findNextJobsToExecute(new Page(0, 100));
      }
    });
  }
}
