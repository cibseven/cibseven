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
package org.cibseven.bpm.integrationtest.jobexecutor;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Timer;
import java.util.TimerTask;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.integrationtest.jobexecutor.beans.ManagedJobExecutorBean;
import org.cibseven.bpm.integrationtest.util.DeploymentHelper;
import org.cibseven.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ArquillianExtension.class)
public class ManagedJobExecutorTest {

  @Deployment
  public static WebArchive createDeployment() {
    WebArchive archive = ShrinkWrap.create(WebArchive.class, "test.war")
        .addAsWebInfResource("org/cibseven/bpm/integrationtest/beans.xml", "beans.xml")
        .addAsLibraries(DeploymentHelper.getEngineCdi())
        .addClass(ManagedJobExecutorTest.class)
        .addClass(ManagedJobExecutorBean.class)
        .addAsResource("org/cibseven/bpm/integrationtest/jobexecutor/ManagedJobExecutorTest.testManagedExecutorUsed.bpmn20.xml");

    TestContainer.addContainerSpecificResourcesForNonPa(archive);

    return archive;
  }

  protected ProcessEngine processEngine;
  protected ManagementService managementService;
  protected RuntimeService runtimeService;

  @BeforeEach
  public void setUpCdiProcessEngineTestCase() throws Exception {
    processEngine = (ProgrammaticBeanLookup.lookup(ManagedJobExecutorBean.class)).getProcessEngine();
    managementService = processEngine.getManagementService();
    runtimeService = processEngine.getRuntimeService();
  }

  @AfterEach
  public void tearDownCdiProcessEngineTestCase() throws Exception {
    processEngine = null;
    managementService = null;
    runtimeService = null;
  }

  @Test
  public void testManagedExecutorUsed() throws InterruptedException {
    org.cibseven.bpm.engine.repository.Deployment deployment = processEngine.getRepositoryService().createDeployment()
      .addClasspathResource("org/cibseven/bpm/integrationtest/jobexecutor/ManagedJobExecutorTest.testManagedExecutorUsed.bpmn20.xml")
      .deploy();

    try {
      String pid = runtimeService.startProcessInstanceByKey("testBusinessProcessScopedWithJobExecutor").getId();

      assertThat(managementService.createJobQuery().processInstanceId(pid).count()).isEqualTo(1L);

      waitForJobExecutorToProcessAllJobs(pid, 5000l, 25l);

      assertThat(managementService.createJobQuery().processInstanceId(pid).count()).isEqualTo(0L);

      assertThat(runtimeService.createVariableInstanceQuery().processInstanceIdIn(pid).variableName("foo").singleResult().getValue()).isEqualTo("bar");
    } finally {
      processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }

  }

  protected void waitForJobExecutorToProcessAllJobs(String processInstanceId, long maxMillisToWait, long intervalMillis) {
    JobExecutor jobExecutor = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration()).getJobExecutor();
    jobExecutor.start();

    try {
      Timer timer = new Timer();
      InteruptTask task = new InteruptTask(Thread.currentThread());
      timer.schedule(task, maxMillisToWait);
      boolean areJobsAvailable = true;
      try {
        while (areJobsAvailable && !task.isTimeLimitExceeded()) {
          Thread.sleep(intervalMillis);
          areJobsAvailable = areJobsAvailable(processInstanceId);
        }
      } catch (InterruptedException e) {
      } finally {
        timer.cancel();
      }
      if (areJobsAvailable) {
        throw new ProcessEngineException("time limit of " + maxMillisToWait + " was exceeded");
      }

    } finally {
      jobExecutor.shutdown();
    }
  }

  private static class InteruptTask extends TimerTask {
    protected boolean timeLimitExceeded = false;
    protected Thread thread;
    public InteruptTask(Thread thread) {
      this.thread = thread;
    }
    public boolean isTimeLimitExceeded() {
      return timeLimitExceeded;
    }
    @Override
    public void run() {
      timeLimitExceeded = true;
      thread.interrupt();
    }
  }

  protected boolean areJobsAvailable(String processInstanceId) {
    return !managementService
      .createJobQuery()
      .processInstanceId(processInstanceId)
      .executable()
      .list()
      .isEmpty();
  }
}