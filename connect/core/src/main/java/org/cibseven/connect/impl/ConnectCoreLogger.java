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
package org.cibseven.connect.impl;

import org.cibseven.connect.ConnectorEndpointNotAllowedException;
import org.cibseven.connect.ConnectorException;
import org.cibseven.connect.spi.CloseableConnectorResponse;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorConfigurator;
import org.cibseven.connect.spi.ConnectorProvider;

public class ConnectCoreLogger extends ConnectLogger {

  public void closingResponse(CloseableConnectorResponse response) {
    logDebug("001", "Closing closeable connector response '{}'", response);
  }

  public void successfullyClosedResponse(CloseableConnectorResponse response) {
    logDebug("002", "Successfully closed closeable connector response '{}'", response);
  }

  public ConnectorException exceptionWhileClosingResponse(Exception cause) {
    return new ConnectorException(exceptionMessage("003", "Unable to close response"), cause);
  }

  public void connectorProviderDiscovered(ConnectorProvider provider, String connectorId, Connector connectorInstance) {
    if (isInfoEnabled()) {
      logInfo("004", "Discovered provider for connector id '{}' and class '{}': '{}'",
        connectorId, connectorInstance.getClass().getName(), provider.getClass().getName());
    }
  }

  public ConnectorException multipleConnectorProvidersFound(String connectorId) {
    return new ConnectorException(exceptionMessage("005", "Multiple providers found for connector '{}'", connectorId));
  }

  public void connectorConfiguratorDiscovered(ConnectorConfigurator configurator) {
    if (isInfoEnabled()) {
      logInfo("006", "Discovered configurator for connector class '{}': '{}'",
        configurator.getConnectorClass().getName(), configurator.getClass().getName());
    }
  }

  public ConnectorEndpointNotAllowedException endpointNotAllowed(String connectorId, String endpoint,
      String source, String systemProperty, String environmentVariable) {
    return new ConnectorEndpointNotAllowedException(exceptionMessage("007",
        "Connector '{}' must not connect to '{}': the endpoint is not allowed by '{}'. The connector's own "
        + "list is set with the system property '{}' or the environment variable '{}'",
        connectorId, endpoint, source, systemProperty, environmentVariable));
  }

  public void endpointRestrictionLifted(String connectorId, String source) {
    logInfo("011", "Connector '{}' is not restricted: '{}' is '*'", connectorId, source);
  }

  public void endpointRestrictionActive(String connectorId, int count, String source) {
    logInfo("008", "Connector '{}' may only connect to the {} endpoint(s) allowed by '{}'", connectorId, count, source);
  }

  public void invalidAllowedEndpoint(String connectorId, int position, String source) {
    logWarn("009", "Ignoring entry {} of '{}' for connector '{}': expected 'scheme://host[:port]', "
        + "optionally with '*.' in front of a host name of at least two labels", position, source, connectorId);
  }

  public void noAllowedEndpoint(String connectorId, String source) {
    logWarn("010", "'{}' contains no valid endpoint, connector '{}' refuses every endpoint", source, connectorId);
  }

}
