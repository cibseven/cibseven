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

import java.util.function.Function;

/**
 * Reads the connector's deployment-level settings — the ones an operator sets
 * with a {@code -D} JVM argument or a container environment variable, not per
 * BPMN task.
 *
 * <p>Every such setting resolves the same way: system property, then environment
 * variable, then the built-in default, with blank values treated as unset. The
 * system property wins so a {@code -D} override can be applied without editing
 * the container environment.
 *
 * <p>This lives in its own class because the settings do not belong to any one
 * consumer: the chat log is read by {@link AgentChatListener}, the store choice
 * by {@link AgentChatMemoryStore} and the payload ceiling by
 * {@link ProcessVariableChatMemoryStore}. Before, the resolution sat on
 * {@code AgentChatListener} and the others reached into the listener for it,
 * which suggested a dependency that is not there.
 */
final class ConnectorSettings {

  /**
   * Environment-variable lookup seam. Defaults to {@link System#getenv(String)};
   * tests replace it to simulate environment variables without spawning a new
   * JVM, and must restore it afterwards — it is static and shared.
   */
  static Function<String, String> ENV_READER = System::getenv;

  private ConnectorSettings() {
    // utility
  }

  /**
   * Returns the configured raw value, trimmed, or {@code null} when neither the
   * system property nor the environment variable is set to anything but blank.
   *
   * <p>Exposed alongside the typed accessors so a caller that needs its own
   * parsing and its own error reporting — the payload ceiling reports an
   * unparseable value and falls back rather than failing — still resolves the
   * value through the same precedence and the same test seam.
   */
  static String resolve(String systemProperty, String envVar) {
    String fromSys = System.getProperty(systemProperty);
    if (fromSys != null && !fromSys.trim().isEmpty()) {
      return fromSys.trim();
    }
    String fromEnv = ENV_READER.apply(envVar);
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return fromEnv.trim();
    }
    return null;
  }

  /**
   * Resolves a boolean setting, falling back to {@code defaultValue}. Anything
   * other than {@code "true"} (case-insensitive) counts as {@code false}, per
   * {@link Boolean#parseBoolean} — a typo therefore reads as {@code false}, so
   * flags whose non-default value is {@code false} announce themselves in the
   * log when they take effect.
   */
  static boolean resolveBoolean(String systemProperty, String envVar, boolean defaultValue) {
    String raw = resolve(systemProperty, envVar);
    return (raw == null) ? defaultValue : Boolean.parseBoolean(raw);
  }

}
