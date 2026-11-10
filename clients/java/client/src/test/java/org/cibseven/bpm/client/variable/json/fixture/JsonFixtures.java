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
package org.cibseven.bpm.client.variable.json.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Byte-exact comparison against the JSON fixtures in {@code src/test/resources/fixtures/}.
 * <p>
 * The fixtures pin the JSON the client writes and reads (stored object variables, type names, REST requests).
 * They were recorded with the Jackson 2 based client and must stay byte-identical with every JSON library
 * the client is built on.
 * <p>
 * To (re-)record them, run the tests with
 * {@code -Dclient.fixtures.write=<module>/src/test/resources/fixtures} and review the diff.
 */
public final class JsonFixtures {

  public static final String WRITE_PROPERTY = "client.fixtures.write";

  private JsonFixtures() {
  }

  public static void assertMatchesFixture(String fixture, String actual) {
    byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);

    String writeDir = System.getProperty(WRITE_PROPERTY);
    if (writeDir != null && !writeDir.isBlank()) {
      // recording mode: the classpath copy is stale until the next build, so do not compare
      write(Paths.get(writeDir, fixture), actualBytes);
      return;
    }

    byte[] expected = read(fixture);
    // compare as text first for a readable diff, then byte-exact
    assertThat(new String(actualBytes, StandardCharsets.UTF_8))
        .as("fixture %s", fixture)
        .isEqualTo(new String(expected, StandardCharsets.UTF_8));
    assertThat(actualBytes).as("fixture %s (bytes)", fixture).isEqualTo(expected);
  }

  public static String readString(String fixture) {
    return new String(read(fixture), StandardCharsets.UTF_8);
  }

  private static byte[] read(String fixture) {
    String resource = "/fixtures/" + fixture;
    try (InputStream in = JsonFixtures.class.getResourceAsStream(resource)) {
      if (in == null) {
        fail("Missing fixture " + resource + "; record it with -D" + WRITE_PROPERTY
            + "=<module>/src/test/resources/fixtures");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path file, byte[] content) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
