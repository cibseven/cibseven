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
package org.cibseven.bpm.engine.impl.scripting.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;

/**
 * The polyglot engine is built with the classloader of GraalJS as the context classloader (CIB7-2871), and the one of
 * the caller comes back afterwards.
 */
public class GraalJsClassLoaderTest {

  protected ClassLoader originalContextClassLoader;
  /** The classloader of a deployment that sees no GraalJS. */
  protected URLClassLoader deployment;

  @BeforeEach
  public void setUp() {
    originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    deployment = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
    Thread.currentThread().setContextClassLoader(deployment);
  }

  @AfterEach
  public void tearDown() throws Exception {
    Thread.currentThread().setContextClassLoader(originalContextClassLoader);
    deployment.close();
  }

  @Test
  public void shouldBuildWithTheClassLoaderOfGraalJsAndRestoreTheCallers() throws ReflectiveOperationException {
    // when
    ClassLoader duringBuild = GraalJsNumberNarrowing.withGraalJsClassLoader(GraalJSScriptEngine.class,
        () -> Thread.currentThread().getContextClassLoader());

    // then
    assertThat(duringBuild).isSameAs(GraalJSScriptEngine.class.getClassLoader());
    assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(deployment);
  }

  @Test
  public void shouldRestoreTheCallersClassLoaderWhenTheBuildFails() {
    // when
    assertThatThrownBy(() -> GraalJsNumberNarrowing.withGraalJsClassLoader(GraalJSScriptEngine.class, () -> {
      throw new IllegalStateException("build failed");
    })).isInstanceOf(IllegalStateException.class);

    // then
    assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(deployment);
  }

}
