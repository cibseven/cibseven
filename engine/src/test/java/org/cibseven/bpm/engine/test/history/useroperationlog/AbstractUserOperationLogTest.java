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
package org.cibseven.bpm.engine.test.history.useroperationlog;

import org.cibseven.bpm.engine.ExternalTaskService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Roman Smirnov
 *
 */
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public abstract class AbstractUserOperationLogTest {

  public static final String USER_ID = "demo";

  @RegisterExtension
  protected org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule engineRule = new org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule();
  @RegisterExtension
  protected org.cibseven.bpm.engine.test.util.ProcessEngineTestRule testRule = new org.cibseven.bpm.engine.test.util.ProcessEngineTestRule(engineRule);
  protected org.cibseven.bpm.engine.IdentityService identityService;
  protected org.cibseven.bpm.engine.RuntimeService runtimeService;
  protected org.cibseven.bpm.engine.TaskService taskService;
  protected org.cibseven.bpm.engine.HistoryService historyService;
  protected org.cibseven.bpm.engine.RepositoryService repositoryService;
  protected org.cibseven.bpm.engine.ManagementService managementService;
  protected org.cibseven.bpm.engine.CaseService caseService;
  protected org.cibseven.bpm.engine.DecisionService decisionService;
  protected org.cibseven.bpm.engine.FormService formService;
  protected ExternalTaskService externalTaskService;
  protected ProcessEngine processEngine;
  protected org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl processEngineConfiguration;


  @BeforeEach
  public void abstractSetUp() throws Exception {
    processEngine = engineRule.getProcessEngine();
    identityService = processEngine.getIdentityService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    historyService = processEngine.getHistoryService();
    repositoryService = processEngine.getRepositoryService();
    managementService = processEngine.getManagementService();
    caseService = processEngine.getCaseService();
    decisionService = processEngine.getDecisionService();
    formService = processEngine.getFormService();
    externalTaskService = processEngine.getExternalTaskService();
    processEngineConfiguration = (org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    identityService.setAuthenticatedUserId(USER_ID);
  }

  @AfterEach
  public void abstractTearDown() throws Exception {
    identityService.clearAuthentication();
  }

}