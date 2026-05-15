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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

/**
 * Coverage tests for the {@link KnowledgeIngestor} CLI entry point.
 *
 * <p>The full {@code main} flow loads a real PDF and connects to pgvector, so
 * these tests focus on the parts that can be exercised in isolation: the
 * private {@code parseArgs} and {@code require} helpers, reached via
 * reflection. The {@code System.exit(1)} branch inside {@code require} is not
 * driven from a test — on the Java 17 baseline used here, intercepting
 * {@code System.exit} requires setting the JVM property
 * {@code -Djava.security.manager=allow}, which the surefire configuration
 * inherited from the connect parent POM does not enable.
 */
public class KnowledgeIngestorMainTest {

  // ── parseArgs ────────────────────────────────────────────────────────────

  @Test
  public void parseArgsShouldReturnEmptyMapForNoArguments() throws Exception {
    Map<String, String> map = invokeParseArgs(new String[] {});
    assertThat(map).isEmpty();
  }

  @Test
  public void parseArgsShouldPairFlagsWithFollowingValues() throws Exception {
    Map<String, String> map = invokeParseArgs(new String[] {
        "--file", "knowledge.pdf",
        "--pgHost", "db.example.test",
        "--pgUser", "u",
        "--pgPassword", "p"
    });
    assertThat(map)
        .containsEntry("--file", "knowledge.pdf")
        .containsEntry("--pgHost", "db.example.test")
        .containsEntry("--pgUser", "u")
        .containsEntry("--pgPassword", "p");
  }

  @Test
  public void parseArgsShouldIgnoreTrailingUnpairedArg() throws Exception {
    // The loop condition is `i < args.length - 1`, so a dangling final token is dropped.
    Map<String, String> map = invokeParseArgs(new String[] {"--file", "x.pdf", "--orphan"});
    assertThat(map).containsExactly(Map.entry("--file", "x.pdf"));
  }

  @Test
  public void parseArgsShouldKeepLastValueForRepeatedFlag() throws Exception {
    Map<String, String> map = invokeParseArgs(new String[] {
        "--file", "first.pdf",
        "--file", "second.pdf"
    });
    assertThat(map).containsEntry("--file", "second.pdf");
  }

  // ── require ──────────────────────────────────────────────────────────────

  @Test
  public void requireShouldReturnValueWhenPresent() throws Exception {
    Map<String, String> args = Map.of("--file", "knowledge.pdf");
    Object value = invokeRequire(args, "--file");
    assertThat(value).isEqualTo("knowledge.pdf");
  }

  // ── Reflection helpers ───────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Map<String, String> invokeParseArgs(String[] args) throws Exception {
    Method m = KnowledgeIngestor.class.getDeclaredMethod("parseArgs", String[].class);
    m.setAccessible(true);
    try {
      return (Map<String, String>) m.invoke(null, (Object) args);
    } catch (InvocationTargetException ite) {
      throw (Exception) ite.getCause();
    }
  }

  private static Object invokeRequire(Map<String, String> params, String key) throws Exception {
    Method m = KnowledgeIngestor.class.getDeclaredMethod("require", Map.class, String.class);
    m.setAccessible(true);
    try {
      return m.invoke(null, params, key);
    } catch (InvocationTargetException ite) {
      throw (Exception) ite.getCause();
    }
  }

}
