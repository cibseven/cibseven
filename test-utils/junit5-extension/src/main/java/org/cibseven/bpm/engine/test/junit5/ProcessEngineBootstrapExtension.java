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
package org.cibseven.bpm.engine.test.junit5;

import java.util.List;
import java.util.function.Consumer;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngines;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.runtime.Job;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ProcessEngineBootstrapExtension implements BeforeAllCallback, AfterAllCallback {

  private ProcessEngine processEngine;
  private final String configurationResource;
  private final Consumer<ProcessEngineConfigurationImpl> processEngineConfigurator;

  public ProcessEngineBootstrapExtension() {
    this("camunda.cfg.xml", null);
  }

  public ProcessEngineBootstrapExtension(String configurationResource) {
    this(configurationResource, null);
  }

  public ProcessEngineBootstrapExtension(Consumer<ProcessEngineConfigurationImpl> processEngineConfigurator) {
    this("camunda.cfg.xml", processEngineConfigurator);
  }

  public ProcessEngineBootstrapExtension(String configurationResource, Consumer<ProcessEngineConfigurationImpl> processEngineConfigurator) {
    this.configurationResource = configurationResource;
    this.processEngineConfigurator = processEngineConfigurator;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    ProcessEngineConfigurationImpl processEngineConfiguration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
      .createProcessEngineConfigurationFromResource(configurationResource);

    if (processEngineConfigurator != null) {
      processEngineConfigurator.accept(processEngineConfiguration);
    }

    this.processEngine = processEngineConfiguration.buildProcessEngine();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    if (processEngine != null) {
      deleteHistoryCleanupJob();
      processEngine.close();
      ProcessEngines.unregister(processEngine);
      processEngine = null;
    }
  }

  public ProcessEngine getProcessEngine() {
    return processEngine;
  }

  private void deleteHistoryCleanupJob() {
    final List<Job> jobs = processEngine.getHistoryService().findHistoryCleanupJobs();
    for (final Job job: jobs) {
      ((ProcessEngineConfigurationImpl)processEngine.getProcessEngineConfiguration()).getCommandExecutorTxRequired().execute(new Command<Void>() {
        public Void execute(CommandContext commandContext) {
          commandContext.getJobManager().deleteJob((JobEntity) job);
          commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(job.getId());
          return null;
        }
      });
    }
  }
}
