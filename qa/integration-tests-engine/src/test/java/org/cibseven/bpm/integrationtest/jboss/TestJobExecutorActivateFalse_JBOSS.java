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
package org.cibseven.bpm.integrationtest.jboss;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * @author Daniel Meyer
 *
 */
@RunWith(Arquillian.class)
public class TestJobExecutorActivateFalse_JBOSS extends AbstractFoxPlatformIntegrationTest {

  @Deployment(name="deployment1")
  public static WebArchive processArchive() {
    return initWebArchiveDeployment("test.war", "jobExecutorActivate-processes.xml");
  }

  @Test
  public void shouldNotActiateJobExecutor() {

    ProcessEngine processEngine = processEngineService.getProcessEngine("jobExecutorActivate-FALSE-engine");
    ProcessEngineConfiguration configuration = processEngine.getProcessEngineConfiguration();
    JobExecutor jobExecutor = ((ProcessEngineConfigurationImpl)configuration).getJobExecutor();
    assertFalse(jobExecutor.isActive());

    processEngine = processEngineService.getProcessEngine("jobExecutorActivate-UNDEFINED-engine");
    configuration = processEngine.getProcessEngineConfiguration();
    jobExecutor = ((ProcessEngineConfigurationImpl)configuration).getJobExecutor();
    assertTrue(jobExecutor.isActive());

  }
}