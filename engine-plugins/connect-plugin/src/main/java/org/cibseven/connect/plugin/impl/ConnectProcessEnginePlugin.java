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
package org.cibseven.connect.plugin.impl;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobHandler;
import org.cibseven.bpm.engine.impl.util.ClassLoaderUtil;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.plugin.impl.agentic.AgenticAdHocParseListener;
import org.cibseven.connect.plugin.impl.agentic.AgenticTurnJobHandler;

public class ConnectProcessEnginePlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    // use classloader which loaded the plugin
    ClassLoader classloader = ClassLoaderUtil.getClassloader(ConnectProcessEnginePlugin.class);
    Connectors.loadConnectors(classloader);

    addConnectorParseListener(processEngineConfiguration);
    addAgenticAdHocParseListener(processEngineConfiguration);
    addAgenticTurnJobHandler(processEngineConfiguration);
  }

  private void addConnectorParseListener(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<BpmnParseListener> preParseListeners = processEngineConfiguration.getCustomPreBPMNParseListeners();
    if(preParseListeners == null) {
      preParseListeners = new ArrayList<BpmnParseListener>();
      processEngineConfiguration.setCustomPreBPMNParseListeners(preParseListeners);
    }
    preParseListeners.add(new ConnectorParseListener());
  }

  /**
   * Post, not pre: it needs the behaviour, the children and their sequence flows, none of which
   * exist while the scope is being built.
   */
  private void addAgenticAdHocParseListener(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<BpmnParseListener> postParseListeners = processEngineConfiguration.getCustomPostBPMNParseListeners();
    if(postParseListeners == null) {
      postParseListeners = new ArrayList<BpmnParseListener>();
      processEngineConfiguration.setCustomPostBPMNParseListeners(postParseListeners);
    }
    postParseListeners.add(new AgenticAdHocParseListener());
  }

  private void addAgenticTurnJobHandler(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<JobHandler> jobHandlers = processEngineConfiguration.getCustomJobHandlers();
    if(jobHandlers == null) {
      jobHandlers = new ArrayList<JobHandler>();
      processEngineConfiguration.setCustomJobHandlers(jobHandlers);
    }
    jobHandlers.add(new AgenticTurnJobHandler());
  }

}