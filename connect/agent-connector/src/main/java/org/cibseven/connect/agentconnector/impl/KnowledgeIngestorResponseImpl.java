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

import java.util.Map;

import org.cibseven.connect.agentconnector.KnowledgeIngestorConnector;
import org.cibseven.connect.agentconnector.KnowledgeIngestorResponse;
import org.cibseven.connect.impl.AbstractConnectorResponse;

/**
 * Default implementation of {@link KnowledgeIngestorResponse}.
 */
public class KnowledgeIngestorResponseImpl extends AbstractConnectorResponse
    implements KnowledgeIngestorResponse {

  private final int chunksIngested;

  public KnowledgeIngestorResponseImpl(int chunksIngested) {
    this.chunksIngested = chunksIngested;
  }

  @Override
  protected void collectResponseParameters(Map<String, Object> responseParameters) {
    responseParameters.put(KnowledgeIngestorConnector.PARAM_NAME_CHUNKS_INGESTED, chunksIngested);
  }

  @Override
  public int getChunksIngested() {
    return chunksIngested;
  }

}
