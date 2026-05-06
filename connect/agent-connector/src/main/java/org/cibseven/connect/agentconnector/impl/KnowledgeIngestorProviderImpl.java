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
package org.cibseven.connect.agentconnector.impl;

import org.cibseven.connect.agentconnector.KnowledgeIngestorConnector;
import org.cibseven.connect.spi.ConnectorProvider;

/**
 * SPI provider that registers the {@link KnowledgeIngestorConnectorImpl} with the
 * CIBseven connect {@link org.cibseven.connect.Connectors} registry.
 *
 * <p>Discovered automatically via {@link java.util.ServiceLoader} through
 * {@code META-INF/services/org.cibseven.connect.spi.ConnectorProvider}.
 */
public class KnowledgeIngestorProviderImpl implements ConnectorProvider {

  @Override
  public String getConnectorId() {
    return KnowledgeIngestorConnector.ID;
  }

  @Override
  public KnowledgeIngestorConnector createConnectorInstance() {
    return new KnowledgeIngestorConnectorImpl();
  }

}
