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
package org.cibseven.connect.ai.agent.impl;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.identity.Authentication;

/**
 * Per-thread carrier used to forward the caller's {@link ProcessEngine}
 * reference and engine authentication from {@link AgentConnectorImpl} into
 * {@link ProcessStarterTool} (and any other future {@code @Tool} class).
 *
 * <p>LangChain4j's {@code AiServices} instantiates each tool class via its
 * public no-arg constructor and exposes no hook to inject per-invocation
 * context, so we cannot pass these values through the tool's fields. The
 * connector pushes them onto this {@link ThreadLocal} just before invoking
 * the agent and clears them in a {@code finally} block; tool methods read
 * them on demand to scope their engine calls under the same user.
 *
 * <p>The engine reference is captured on the connector's calling thread —
 * where {@code BpmPlatform.getDefaultProcessEngine()} is reliably resolvable —
 * because the same lookup can return {@code null} on a LangChain4j worker
 * thread (different context classloader, no command context).
 */
public final class ProcessStarterToolContext {

  private static final ThreadLocal<ProcessEngine> ENGINE = new ThreadLocal<>();
  private static final ThreadLocal<Authentication> AUTH = new ThreadLocal<>();
  /**
   * Pointer to the {@link AgentChatListener} owning the in-flight model call.
   * Tools (e.g. {@link ProcessStarterTool}) publish side-effect records here so
   * the listener can stitch the resulting {@code processInstanceId} and the
   * executing principal into the audit event for the LLM turn that triggered
   * the tool.
   */
  private static final ThreadLocal<AgentChatListener> LISTENER = new ThreadLocal<>();

  private ProcessStarterToolContext() {
  }

  public static void setEngine(ProcessEngine engine) {
    if (engine == null) {
      ENGINE.remove();
    } else {
      ENGINE.set(engine);
    }
  }

  public static ProcessEngine getEngine() {
    return ENGINE.get();
  }

  public static void setAuthentication(Authentication authentication) {
    if (authentication == null) {
      AUTH.remove();
    } else {
      AUTH.set(authentication);
    }
  }

  public static Authentication getAuthentication() {
    return AUTH.get();
  }

  static void setActiveListener(AgentChatListener listener) {
    if (listener == null) {
      LISTENER.remove();
    } else {
      LISTENER.set(listener);
    }
  }

  static AgentChatListener getActiveListener() {
    return LISTENER.get();
  }

  public static void clear() {
    ENGINE.remove();
    AUTH.remove();
    LISTENER.remove();
  }
}
