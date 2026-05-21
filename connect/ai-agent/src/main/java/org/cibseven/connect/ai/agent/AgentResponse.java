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
package org.cibseven.connect.ai.agent;

import org.cibseven.connect.spi.ConnectorResponse;

/**
 * Response interface for the LangChain4j agent connector.
 *
 * <p>After a successful execution the following output parameter is available
 * for BPMN output mapping:
 * <ul>
 *   <li>{@code output} — the final text response produced by the agent</li>
 *   <li>{@code outputAiMeta} — EU AI Act Art. 50(2) marker. A
 *       {@code Map<String,Object>} carrying {@code aiGenerated=true},
 *       {@code runId}, {@code provider}, {@code model}, {@code responseId},
 *       and {@code generatedAt}. Mapped as a sibling of {@code output}
 *       (typically named {@code <outputVar>__aiMeta}) so downstream Human
 *       Tasks, gateways, and audit consumers can detect AI-generated content
 *       without parsing the answer text</li>
 *   <li>{@code memoryId} — identifier of the chat-memory entry used by this
 *       invocation (only populated when {@code useChatMemory} was active);
 *       persist this value into a process variable to continue the same
 *       conversation on a subsequent invocation, e.g. after a human task</li>
 * </ul>
 *
 * <p>The chat log is not returned via the response: the connector writes/updates a
 * process variable named {@code AGENT_CONNECTOR_LOG_PREFIX + <activityId>} directly
 * during the invocation.
 */
public interface AgentResponse extends ConnectorResponse {

  /** Returns the final text response produced by the agent. */
  String getOutput();

  /**
   * Returns the EU AI Act Art. 50(2) marker describing the AI-generated nature
   * of {@link #getOutput()}, or {@code null} when no marker could be assembled
   * (e.g. the agent returned no successful response).
   */
  java.util.Map<String, Object> getOutputAiMeta();

  /**
   * Returns the chat memory identifier used during this invocation, or
   * {@code null} when chat memory was not activated.
   */
  String getMemoryId();

}
