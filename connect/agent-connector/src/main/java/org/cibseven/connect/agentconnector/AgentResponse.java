/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
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
package org.cibseven.connect.agentconnector;

import org.cibseven.connect.spi.ConnectorResponse;

/**
 * Response interface for the LangChain4j agent connector.
 *
 * <p>After a successful execution the following output parameters are available
 * for BPMN output mapping:
 * <ul>
 *   <li>{@code output} — the final text response produced by the agent</li>
 *   <li>{@code chatLog} — JSON-serialised chat log for the current invocation</li>
 * </ul>
 */
public interface AgentResponse extends ConnectorResponse {

  /** Returns the final text response produced by the agent. */
  String getOutput();

  /** Returns the JSON-serialised chat log for the current invocation, never null. */
  String getChatLog();

}
