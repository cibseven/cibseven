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
package org.cibseven.bpm.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cibseven.bpm.client.exception.ExternalTaskClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JacksonVariantCheckTest {

  @TempDir
  Path tempDir;

  @Test
  public void shouldContainExactlyOneMarker() {
    ClassLoader classLoader = JacksonVariantCheck.class.getClassLoader();

    assertThat(classLoader.getResource(JacksonVariantCheck.JACKSON3_MARKER) != null)
        .isNotEqualTo(classLoader.getResource(JacksonVariantCheck.JACKSON2_MARKER) != null);
    assertThatCode(() -> JacksonVariantCheck.ensureSingleVariant(classLoader)).doesNotThrowAnyException();
  }

  @Test
  public void shouldAcceptOneVariant() throws IOException {
    try (URLClassLoader jackson3 = classLoader(JacksonVariantCheck.JACKSON3_MARKER);
        URLClassLoader jackson2 = classLoader(JacksonVariantCheck.JACKSON2_MARKER)) {
      assertThatCode(() -> JacksonVariantCheck.ensureSingleVariant(jackson3)).doesNotThrowAnyException();
      assertThatCode(() -> JacksonVariantCheck.ensureSingleVariant(jackson2)).doesNotThrowAnyException();
    }
  }

  @Test
  public void shouldRejectBothVariants() throws IOException {
    try (URLClassLoader both = classLoader(JacksonVariantCheck.JACKSON3_MARKER, JacksonVariantCheck.JACKSON2_MARKER)) {
      assertThatThrownBy(() -> JacksonVariantCheck.ensureSingleVariant(both))
          .isInstanceOf(ExternalTaskClientException.class)
          .hasMessageContaining("TASK/CLIENT-01035")
          .hasMessageContaining("keep exactly one of them");
    }
  }

  protected URLClassLoader classLoader(String... markers) throws IOException {
    URL[] urls = new URL[markers.length];
    for (int i = 0; i < markers.length; i++) {
      Path root = Files.createDirectories(tempDir.resolve("cp" + i + "-" + System.nanoTime()));
      Path marker = root.resolve(markers[i]);
      Files.createDirectories(marker.getParent());
      Files.writeString(marker, "marker");
      urls[i] = root.toUri().toURL();
    }
    // no parent: only the given roots are visible
    return new URLClassLoader(urls, null);
  }

}
