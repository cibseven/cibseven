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
package org.cibseven.connect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.connect.ai.agent.impl.ProcessStarterToolContext;
import org.cibseven.connect.impl.AbstractConnector;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorRequest;
import org.cibseven.connect.spi.ConnectorResponse;

/**
 * Stands in for the language model while leaving the rest of a turn real.
 *
 * <p>A turn is a job on the scope execution, and everything around the model call — reading
 * the scope's configuration, setting the execution context the tool needs, deciding at the end
 * of the turn whether the scope lives on — belongs to the engine plugin. A test that wants to
 * say "the agent starts this, then that" has to get in at the one place that is not the
 * engine's: the connector.
 *
 * <p>So this registers itself under the real connector id for the duration of a test and runs
 * Java instead of talking to a model. The turn around it is the production one.
 *
 * <p>The alternative is an HTTP stub answering with scripted tool calls, which
 * {@code AgenticTurnJobTest} does. That covers the connector too and is the right shape for a
 * handful of end-to-end cases; it is the wrong shape for a class that scripts sixty different
 * turns, because every step then has to be expressed as JSON a model might have produced.
 */
public final class ScriptedAgent
    extends AbstractConnector<ScriptedAgent.Request, ScriptedAgent.Response> {

  /** The id the engine plugin looks for. Taking it over is the whole trick. */
  public static final String CONNECTOR_ID = "cibseven-ai-agent";

  /** One turn's worth of agent behaviour. */
  public interface Turn {
    /**
     * @param parameters what the plugin passed to the connector, so a test can assert on the
     *     configuration that reached it
     * @return the agent's answer, which the plugin writes to the scope's result variable
     */
    String run(Map<String, Object> parameters) throws Exception;
  }

  private static final List<Turn> SCRIPT = new ArrayList<Turn>();
  private static final List<Map<String, Object>> REQUESTS = new ArrayList<Map<String, Object>>();
  private static final List<Exception> FAILURES = new ArrayList<Exception>();
  private static int turnsTaken;

  private static Connector<?> replaced;

  private ScriptedAgent() {
    super(CONNECTOR_ID);
  }

  // --- installing ------------------------------------------------------------

  /**
   * Takes the connector id over and queues {@code script}, one entry per turn. A turn beyond
   * the end of the script does nothing and answers with a fixed string, so a test that runs
   * one turn too many fails on its own assertion rather than here.
   *
   * <p>Call this <em>after</em> building the process engine. {@code ConnectProcessEnginePlugin}
   * rebuilds the connector registry from the classpath as the engine starts, which throws any
   * earlier installation away and leaves the test talking to a real language model.
   */
  public static void install(Turn... script) {
    reset();
    for (Turn turn : script) {
      SCRIPT.add(turn);
    }
    if (replaced == null) {
      replaced = Connectors.getConnector(CONNECTOR_ID);
    }
    Connectors.registerConnector(CONNECTOR_ID, new ScriptedAgent());
  }

  /** Puts the real connector back. Safe to call when nothing was installed. */
  public static void uninstall() {
    if (replaced != null) {
      Connectors.registerConnector(CONNECTOR_ID, replaced);
      replaced = null;
    }
    reset();
  }

  public static void reset() {
    SCRIPT.clear();
    REQUESTS.clear();
    FAILURES.clear();
    turnsTaken = 0;
  }

  // --- what the script did ---------------------------------------------------

  /** Turns actually run, including those past the end of the script. */
  public static int turnsTaken() {
    return turnsTaken;
  }

  /** The parameters of each turn, in order. */
  public static List<Map<String, Object>> requests() {
    return REQUESTS;
  }

  /**
   * What a turn threw, in order.
   *
   * <p>A turn that throws is a turn the agent could not finish, and the engine turns that into
   * a failed job. Tests that are about the exception itself — a refusal from the tool, say —
   * read it here rather than unwrapping a job failure.
   */
  public static List<Exception> failures() {
    return FAILURES;
  }

  // --- the connector ---------------------------------------------------------

  @Override
  public Request createRequest() {
    return new Request();
  }

  /** The plugin goes through {@code request.execute()}; this is here for the interface. */
  @Override
  public ConnectorResponse execute(Request request) {
    return request.execute();
  }

  public final class Request implements ConnectorRequest<Response> {

    private final Map<String, Object> parameters = new HashMap<String, Object>();

    @Override
    public void setRequestParameters(Map<String, Object> params) {
      parameters.putAll(params);
    }

    @Override
    public void setRequestParameter(String name, Object value) {
      parameters.put(name, value);
    }

    @Override
    public Map<String, Object> getRequestParameters() {
      return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V getRequestParameter(String name) {
      return (V) parameters.get(name);
    }

    @Override
    public Response execute() {
      REQUESTS.add(new HashMap<String, Object>(parameters));
      int index = turnsTaken++;

      // The tool reaches the engine through this, the way the real connector arranges it.
      ProcessEngine engine = Context.getProcessEngineConfiguration().getProcessEngine();
      ProcessStarterToolContext.setEngine(engine);
      try {
        if (index >= SCRIPT.size()) {
          return new Response("no script left");
        }
        return new Response(SCRIPT.get(index).run(parameters));
      } catch (Exception e) {
        FAILURES.add(e);
        throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
      } finally {
        ProcessStarterToolContext.clear();
      }
    }
  }

  public static final class Response implements ConnectorResponse {

    private final Map<String, Object> parameters = new HashMap<String, Object>();

    Response(String answer) {
      parameters.put("output", answer);
    }

    @Override
    public Map<String, Object> getResponseParameters() {
      return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V getResponseParameter(String name) {
      return (V) parameters.get(name);
    }
  }
}
