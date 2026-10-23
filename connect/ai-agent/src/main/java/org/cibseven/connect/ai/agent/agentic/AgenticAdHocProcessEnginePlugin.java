/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.agentic;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobHandler;

/**
 * What an agentic ad hoc sub process needs from the engine: the parse listener that
 * recognises one, and the handler that runs a turn.
 *
 * <p>Lives here rather than in the generic connect plugin, where it sat while this was
 * being built. A connector is found through {@code ServiceLoader<ConnectorProvider>}
 * and cannot register either of these itself — that needs a process engine plugin — so
 * the agentic machinery ended up in the one plugin every distribution already
 * registers. The cost was a generic plugin that knew about AI, and AI hooks in every
 * engine that uses connectors at all.
 *
 * <p>{@code ConnectProcessEnginePlugin} now looks for plugins a connector declares,
 * with the same classloader it loads the connectors with, so this one is picked up
 * without a single distribution configuration changing. Declared in
 * {@code META-INF/services/org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin}.
 *
 * <p>An engine without this module on the classpath therefore has no agentic hooks at
 * all, rather than hooks that do nothing. A model carrying
 * {@code cibseven.agentic.enabled} then deploys as an ordinary ad hoc sub process and
 * ends when it is entered — the same outcome as before, reached without the engine
 * carrying the machinery.
 */
public class AgenticAdHocProcessEnginePlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    addParseListener(processEngineConfiguration);
    addTurnJobHandler(processEngineConfiguration);
  }

  /**
   * Post, not pre: it needs the behaviour, the children and their sequence flows, none
   * of which exist while the scope is being built.
   */
  protected void addParseListener(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<BpmnParseListener> postParseListeners =
        processEngineConfiguration.getCustomPostBPMNParseListeners();
    if (postParseListeners == null) {
      postParseListeners = new ArrayList<BpmnParseListener>();
      processEngineConfiguration.setCustomPostBPMNParseListeners(postParseListeners);
    }
    postParseListeners.add(new AgenticAdHocParseListener());
  }

  protected void addTurnJobHandler(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<JobHandler> jobHandlers = processEngineConfiguration.getCustomJobHandlers();
    if (jobHandlers == null) {
      jobHandlers = new ArrayList<JobHandler>();
      processEngineConfiguration.setCustomJobHandlers(jobHandlers);
    }
    jobHandlers.add(new AgenticTurnJobHandler());
  }
}
