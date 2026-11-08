/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.impl.el;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.stream.Stream;

import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.cibseven.bpm.impl.juel.SimpleContext;
import org.cibseven.bpm.impl.juel.SimpleResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs {@code ELContext#convertToType} of the engine's {@link ProcessEngineElContext} and of JUEL's
 * {@link SimpleContext} on Tomcat's EL API, with Expressly as the global EL implementation.
 * Tomcat's API, like the ones of WildFly and JBoss EAP, ignores the {@code ExpressionFactory} in
 * the context, so without the override in both contexts {@code null} would arrive as {@code null}
 * instead of {@code 0} or {@code false} (CIB7-2609).
 * <p>
 * The classes are loaded in an isolated class loader with {@code tomcat-el-api} (on the test class
 * path through {@code tomcat-catalina}), Expressly, the engine and JUEL, so that Tomcat's
 * {@code jakarta.el} classes are used instead of the upstream ones.
 */
public class ConvertToTypeOnTomcatElApiTest {

  static Stream<Arguments> nullConversions() {
    return Stream.of(
        Arguments.of(Long.class, 0L),
        Arguments.of(Boolean.class, false),
        Arguments.of(BigDecimal.class, BigDecimal.ZERO));
  }

  @Test
  public void shouldIgnoreTheFactoryInTheContextOnTomcatsElApi() throws Exception {
    // the precondition of this test: an ELContext without the override passes null on
    try (TomcatElApi el = new TomcatElApi()) {
      Object context = el.newInstance("jakarta.el.StandardELContext", el.type("jakarta.el.ExpressionFactory"), el.juelFactory());

      assertThat(el.convertNull(context, Long.class)).isNull();
    }
  }

  @ParameterizedTest
  @MethodSource("nullConversions")
  public void shouldConvertNullWithJuelInTheEngineContext(Class<?> type, Object expected) throws Exception {
    try (TomcatElApi el = new TomcatElApi()) {
      Object context = el.newInstance(ProcessEngineElContext.class.getName(),
          el.type("jakarta.el.FunctionMapper"), null,
          el.type("jakarta.el.ELResolver"), el.newInstance(SimpleResolver.class.getName()));

      assertThat(el.convertNull(context, type)).isEqualTo(expected);
    }
  }

  @ParameterizedTest
  @MethodSource("nullConversions")
  public void shouldConvertNullWithJuelInTheJuelContext(Class<?> type, Object expected) throws Exception {
    try (TomcatElApi el = new TomcatElApi()) {
      Object context = el.newInstance(SimpleContext.class.getName(),
          el.type("jakarta.el.ELResolver"), el.newInstance(SimpleResolver.class.getName()));

      assertThat(el.convertNull(context, type)).isEqualTo(expected);
    }
  }

  /**
   * Tomcat's EL API, Expressly, the engine and JUEL in an isolated class loader, which is also the
   * context class loader while it is open, so that Tomcat's {@code ELManager} finds Expressly.
   */
  protected static class TomcatElApi implements AutoCloseable {

    protected final URLClassLoader classLoader;
    protected final ClassLoader previousContextClassLoader;

    public TomcatElApi() throws Exception {
      classLoader = new URLClassLoader(new URL[] {
          jarContaining("jakarta/el/ELContext.class", "tomcat-el-api"),
          jarContaining("META-INF/services/jakarta.el.ExpressionFactory", "expressly"),
          location(ProcessEngineElContext.class),
          location(ExpressionFactoryImpl.class)
        }, ClassLoader.getPlatformClassLoader());

      previousContextClassLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(classLoader);

      assertThat(location(type("jakarta.el.ELContext")).toString()).contains("tomcat-el-api");
    }

    public Class<?> type(String name) throws ClassNotFoundException {
      return classLoader.loadClass(name);
    }

    /**
     * @param typesAndArguments the parameter types and arguments of the constructor, alternating
     */
    public Object newInstance(String className, Object... typesAndArguments) throws Exception {
      Class<?>[] types = new Class<?>[typesAndArguments.length / 2];
      Object[] arguments = new Object[types.length];
      for (int i = 0; i < types.length; i++) {
        types[i] = (Class<?>) typesAndArguments[2 * i];
        arguments[i] = typesAndArguments[2 * i + 1];
      }
      return type(className).getConstructor(types).newInstance(arguments);
    }

    public Object juelFactory() throws Exception {
      return newInstance(ExpressionFactoryImpl.class.getName());
    }

    /**
     * Puts JUEL's factory into the context and converts {@code null} to the given type.
     */
    public Object convertNull(Object context, Class<?> type) throws Exception {
      Class<?> elContext = type("jakarta.el.ELContext");
      elContext.getMethod("putContext", Class.class, Object.class)
          .invoke(context, type("jakarta.el.ExpressionFactory"), juelFactory());
      return elContext.getMethod("convertToType", Object.class, Class.class).invoke(context, null, type);
    }

    @Override
    public void close() throws Exception {
      Thread.currentThread().setContextClassLoader(previousContextClassLoader);
      classLoader.close();
    }

    protected static URL location(Class<?> type) {
      return type.getProtectionDomain().getCodeSource().getLocation();
    }

    protected static URL jarContaining(String resource, String jarName) throws Exception {
      ClassLoader testClassLoader = ConvertToTypeOnTomcatElApiTest.class.getClassLoader();
      for (URL url : Collections.list(testClassLoader.getResources(resource))) {
        if ("jar".equals(url.getProtocol()) && url.getPath().contains(jarName)) {
          return ((JarURLConnection) url.openConnection()).getJarFileURL();
        }
      }
      throw new AssertionError("no " + jarName + " jar with " + resource + " on the test class path");
    }
  }

}
