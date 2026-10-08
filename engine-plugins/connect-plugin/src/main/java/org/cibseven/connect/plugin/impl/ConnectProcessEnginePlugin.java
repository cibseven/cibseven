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
import java.util.ServiceLoader;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.util.ClassLoaderUtil;
import org.cibseven.connect.Connectors;

/**
 * Wires the connectors into an engine, and carries whatever plugins a connector brings
 * with it.
 *
 * <p>The second part exists because a connector is found through
 * {@code ServiceLoader<ConnectorProvider>} and nothing else: it cannot register a parse
 * listener or a job handler, which needs a {@link ProcessEnginePlugin}. A connector that
 * needs one therefore declares it in {@code META-INF/services} and is picked up here,
 * with the same classloader the connectors themselves are loaded with.
 *
 * <p>This keeps the distributions untouched — every one of them already registers this
 * plugin — without this class knowing anything about what a connector does. The
 * alternative was naming each connector's plugin in about ten configurations.
 */
public class ConnectProcessEnginePlugin extends AbstractProcessEnginePlugin {

  /** Discovered in {@link #preInit}, so the later two phases reach the same instances. */
  protected List<ProcessEnginePlugin> connectorPlugins = new ArrayList<ProcessEnginePlugin>();

  @Override
  public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    // use classloader which loaded the plugin
    ClassLoader classloader = ClassLoaderUtil.getClassloader(ConnectProcessEnginePlugin.class);
    Connectors.loadConnectors(classloader);

    addConnectorParseListener(processEngineConfiguration);

    connectorPlugins = loadConnectorPlugins(classloader);
    for (ProcessEnginePlugin plugin : connectorPlugins) {
      plugin.preInit(processEngineConfiguration);
    }
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
    for (ProcessEnginePlugin plugin : connectorPlugins) {
      plugin.postInit(processEngineConfiguration);
    }
  }

  @Override
  public void postProcessEngineBuild(ProcessEngine processEngine) {
    for (ProcessEnginePlugin plugin : connectorPlugins) {
      plugin.postProcessEngineBuild(processEngine);
    }
  }

  /**
   * The plugins the connectors on this classloader declare.
   *
   * <p>Deliberately the whole {@link ProcessEnginePlugin} contract rather than a hook of
   * this class's own: a second, weaker plugin interface beside the engine's would have to
   * grow every capability the real one already has.
   *
   * <p>This class is itself a {@code ProcessEnginePlugin} and would be found by the same
   * lookup if it ever declared itself, so it is filtered out — recursion here would be a
   * stack overflow at engine start.
   *
   * <p>Under WildFly this works for the same reason the connectors themselves are found:
   * this module's {@code module.xml} imports each connector module with
   * {@code services="import"}, which is what makes their {@code META-INF/services}
   * entries visible here. A connector module that is absent — the ai-agent one is
   * declared {@code optional="true"} — simply contributes nothing.
   */
  protected List<ProcessEnginePlugin> loadConnectorPlugins(ClassLoader classloader) {
    List<ProcessEnginePlugin> found = new ArrayList<ProcessEnginePlugin>();
    for (ProcessEnginePlugin plugin : ServiceLoader.load(ProcessEnginePlugin.class, classloader)) {
      if (!(plugin instanceof ConnectProcessEnginePlugin)) {
        found.add(plugin);
      }
    }
    return found;
  }

  private void addConnectorParseListener(ProcessEngineConfigurationImpl processEngineConfiguration) {
    List<BpmnParseListener> preParseListeners = processEngineConfiguration.getCustomPreBPMNParseListeners();
    if(preParseListeners == null) {
      preParseListeners = new ArrayList<BpmnParseListener>();
      processEngineConfiguration.setCustomPreBPMNParseListeners(preParseListeners);
    }
    preParseListeners.add(new ConnectorParseListener());
  }

}