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

import org.cibseven.connect.spi.Connector;

/**
 * Puts a connector of a test's own under an existing id for the duration of that test.
 *
 * <p>{@link Connectors#registerConnector} and {@link Connectors#unregisterConnector} are {@code
 * protected static}, so a test outside this package cannot reach them; {@code ScriptedAgent}
 * lives here for the same reason. This is the same trick without the scripting: the connector a
 * test installs is a real one, usually a subclass with a single seam overridden.
 *
 * <p>Install <em>after</em> building the process engine. {@code ConnectProcessEnginePlugin}
 * rebuilds the connector registry from the classpath as the engine starts, which would throw an
 * earlier installation away.
 */
public final class ConnectorSwap {

  private final String connectorId;
  private boolean installed;
  private Connector<?> replaced;

  public ConnectorSwap(String connectorId) {
    this.connectorId = connectorId;
  }

  /**
   * Takes the id over, remembering whatever held it — including nothing at all, so {@link
   * #restore} can tell "nothing was registered" apart from "I have not looked yet" and put the
   * registry back exactly as it found it either way. {@code installed} is what makes that
   * distinction, because {@code replaced == null} cannot: both "nothing held the id" and "this is
   * the first call" leave it null.
   *
   * <p>Safe to call more than once in the same test — a later call only ever replaces what this
   * swap itself installed, never re-reads the registry and mistakes its own earlier installation
   * for the original.
   */
  public void install(Connector<?> connector) {
    if (!installed) {
      replaced = Connectors.getConnector(connectorId);
      installed = true;
    }
    Connectors.registerConnector(connectorId, connector);
  }

  /**
   * Puts the registry back the way {@link #install} found it: the original connector if there
   * was one, or nothing at all if there was not. Safe to call when nothing was installed.
   *
   * <p>{@code Connectors.unregisterConnector} is what the "nothing at all" half needs —
   * registering {@code null} under the id would leave {@code getConnector} returning {@code
   * null} but {@code availableConnectors} still holding the key, which is not the same registry
   * state {@code install} found.
   */
  public void restore() {
    if (!installed) {
      return;
    }
    if (replaced != null) {
      Connectors.registerConnector(connectorId, replaced);
    } else {
      Connectors.unregisterConnector(connectorId);
    }
    installed = false;
    replaced = null;
  }
}
