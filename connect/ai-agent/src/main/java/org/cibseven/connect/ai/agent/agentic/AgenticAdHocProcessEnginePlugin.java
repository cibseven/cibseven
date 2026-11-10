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

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;
import org.cibseven.bpm.engine.impl.jobexecutor.JobHandler;
import org.cibseven.bpm.engine.impl.migration.validation.instance.MigratingActivityInstanceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an agentic ad hoc sub process needs from the engine: the parse listener that
 * recognises one, the handler that runs a turn, and the retry of a failed turn.
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
 * all, rather than hooks that do nothing. A model carrying the agent's connector on an
 * ad hoc sub process then deploys as an ordinary ad hoc sub process, which waits for its
 * children to be activated from outside and is driven by no agent — the same outcome as
 * before, reached without the engine carrying the machinery.
 *
 * <p>One piece is the exception, on purpose (CIB7-2136): the expression function
 * {@code toolParameter}, with which a tool declares a parameter in its input mapping, is
 * registered by {@code ConnectProcessEnginePlugin}, not here. An expression binds its functions
 * when a model is parsed, so a model using it has to parse on an engine without this module
 * too, and a definition deployed earlier has to load after the agent was switched off. There
 * the function only hands its value on; a tool activated from outside that reads
 * {@code toolCall} must be given one, since without this module no scope keeps an empty one.
 *
 * <p><b>No switch of its own</b> (CIB7-2145): agentic ad hoc sub processes are on and off with
 * the AI agent connector, because this plugin ships in its jar and is found with its
 * classloader — on CIB seven Run {@code AI_AGENT_ENABLED}, {@code --production} and
 * {@code --ai}, on Tomcat {@code lib-ai} on the {@code common.loader}, on WildFly the optional
 * import of the ai-agent module by the connect plugin's {@code module.xml}. Switched off they
 * go quietly, unlike an AI agent service task, which fails with "No connector found": a model
 * asking for an agent deploys as a plain ad hoc scope, and an agent waiting for a tool when it
 * was switched off ends with its last answer once that tool ends.
 */
public class AgenticAdHocProcessEnginePlugin extends AbstractProcessEnginePlugin {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticAdHocProcessEnginePlugin.class);

  @Override
  public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    addParseListener(processEngineConfiguration);
    addTurnJobHandler(processEngineConfiguration);
    addConversationMigrationValidator(processEngineConfiguration);
  }

  /**
   * Post, not pre, the same reason the default validators are appended rather than replaced:
   * {@code initMigratingActivityInstanceValidators()} only reads the custom lists once, during
   * the engine's own init, by which point this plugin's {@code preInit} has already run.
   */
  protected void addConversationMigrationValidator(
      ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<MigratingActivityInstanceValidator> validators =
        processEngineConfiguration.getCustomPostMigratingActivityInstanceValidators();
    if (validators == null) {
      validators = new ArrayList<MigratingActivityInstanceValidator>();
      processEngineConfiguration.setCustomPostMigratingActivityInstanceValidators(validators);
    }
    validators.add(new AdHocConversationMigrationValidator());
  }

  /**
   * Post, not pre: it needs the behaviour, the children and their sequence flows, none
   * of which exist while the scope is being built.
   */
  /**
   * After the engine has set up its own failed-job factory, a failed turn job is handed to the
   * turn's retry cycle, and every other job to that factory (CIB7-2149).
   */
  @Override
  public void postInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    FailedJobCommandFactory factory = processEngineConfiguration.getFailedJobCommandFactory();
    if (!(factory instanceof AgenticTurnFailedJobCommandFactory)) {
      processEngineConfiguration.setFailedJobCommandFactory(
          new AgenticTurnFailedJobCommandFactory(factory));
    }
    // The function toolParameter (CIB7-2136) is not registered here: the connect plugin does,
    // so a model using it still parses while this plugin is absent.
  }

  /**
   * Says so in the log when the engine lacks the function {@code toolParameter} (CIB7-2136).
   * The connect plugin registers it, and the two jars come from one build in every
   * distribution; an older connect plugin beside this connector would otherwise show up only as a
   * bare parse error when a model declaring a tool parameter in an input mapping is deployed.
   */
  @Override
  public void postProcessEngineBuild(ProcessEngine processEngine) {
    try {
      ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
          .getExpressionManager().createExpression("${" + AgenticToolParameterFunction.NAME
              + "(null)}");
    } catch (RuntimeException e) {
      LOG.error("The expression function '{}' is not registered on engine '{}', so a model that"
          + " declares a tool parameter in an input mapping will not deploy. The connect plugin"
          + " (ConnectProcessEnginePlugin) of the same build as the AI agent connector registers"
          + " it; this engine runs an older one.", AgenticToolParameterFunction.NAME,
          processEngine.getName());
    }
  }

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
