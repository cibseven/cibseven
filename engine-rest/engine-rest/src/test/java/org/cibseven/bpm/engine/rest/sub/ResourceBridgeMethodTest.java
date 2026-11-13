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
package org.cibseven.bpm.engine.rest.sub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards against CIB7-2558. Since RESTEasy 7.0.3 (RESTEASY-3621) a public bridge method is registered as a
 * resource method when its class declares no real method of the same name. The RESTEasy validator provider
 * (shipped with WildFly) then fails on that bridge with an ArrayIndexOutOfBoundsException, so every request to
 * the resource answers 500. Such a bridge appears when a class inherits a generic method, for example
 * {@code DTO getResource(boolean)} of {@link AbstractResourceProvider}, and implements an interface that
 * declares the method with a concrete return type. The fix is to declare the method in the class itself.
 *
 * The engine-rest test runtimes do not include the validator provider, so this reflection check stands in for it.
 */
public class ResourceBridgeMethodTest {

  private static final String REST_PACKAGE = "org.cibseven.bpm.engine.rest";

  @Test
  public void shouldDeclareEveryPublicBridgedMethodInTheClassItself() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Class<?> clazz : engineRestClasses()) {
      Method[] declared = clazz.getDeclaredMethods();
      for (Method bridge : declared) {
        if (!bridge.isBridge() || !Modifier.isPublic(bridge.getModifiers())) {
          continue;
        }
        boolean declaredForReal = Arrays.stream(declared)
            .anyMatch(m -> !m.isBridge() && !m.isSynthetic() && m.getName().equals(bridge.getName()));
        if (!declaredForReal) {
          violations.add(clazz.getName() + "#" + bridge.getName());
        }
      }
    }

    assertThat(violations)
        .describedAs("public bridge methods without a declared method of the same name (CIB7-2558)")
        .isEmpty();
  }

  private static List<Class<?>> engineRestClasses() throws IOException, URISyntaxException, ClassNotFoundException {
    Path root = Paths.get(AbstractResourceProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path packageDir = root.resolve(REST_PACKAGE.replace('.', '/'));
    assertThat(packageDir).isDirectory();

    List<String> classNames = new ArrayList<>();
    try (Stream<Path> files = Files.walk(packageDir)) {
      files.filter(p -> p.toString().endsWith(".class"))
          .map(p -> root.relativize(p).toString())
          .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
          .forEach(classNames::add);
    }

    List<Class<?>> classes = new ArrayList<>();
    ClassLoader loader = ResourceBridgeMethodTest.class.getClassLoader();
    for (String name : classNames) {
      classes.add(Class.forName(name, false, loader));
    }
    assertThat(classes).isNotEmpty();
    return classes;
  }

}
