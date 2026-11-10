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
package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.TimeZone;

/**
 * Golden files that pin the JSON wire format of the REST API.
 *
 * <p>The fixtures were generated once from the Jackson 2 based REST API and must stay
 * byte-identical when the Jackson major version (or its configuration) changes. A changed
 * fixture means a changed REST API for clients: fix the mapper configuration, not the file.
 *
 * <p>The files are read from the source tree, not from the class path, because the test
 * resources of this module are filtered by Maven (<code>@</code> and <code>${}</code>
 * would be interpolated). Each file holds the exact bytes on the wire plus one trailing
 * line feed.
 *
 * <p>To (re)generate the files after an intended API change, run the test with
 * <code>-Dcibseven.wire.update=true</code> and review the diff.
 */
public final class WireFixtures {

  /** Non-UTC zone with daylight saving time, so that a wrong zone shows up in every date. */
  public static final TimeZone ZONE = TimeZone.getTimeZone("Europe/Berlin");

  static final Path ROOT = Paths.get("src", "test", "resources", "org", "cibseven", "bpm", "engine", "rest", "mapper", "wire");
  static final boolean UPDATE = Boolean.getBoolean("cibseven.wire.update");

  private WireFixtures() {
  }

  public static void assertMatches(String fixture, byte[] actual) throws IOException {
    Path file = ROOT.resolve(fixture);
    if (UPDATE) {
      Files.createDirectories(file.getParent());
      byte[] content = Arrays.copyOf(actual, actual.length + 1);
      content[actual.length] = '\n';
      Files.write(file, content);
      return;
    }
    if (!Files.exists(file)) {
      fail("Missing wire fixture " + file.toAbsolutePath() + "; run with -Dcibseven.wire.update=true to create it. Actual:\n"
          + new String(actual, StandardCharsets.UTF_8));
    }
    byte[] content = Files.readAllBytes(file);
    byte[] expected = content.length > 0 && content[content.length - 1] == '\n'
        ? Arrays.copyOf(content, content.length - 1)
        : content;
    // compare as text first for a readable diff, then byte by byte (encoding, escaping)
    assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8),
        "REST JSON wire format changed: " + fixture);
    if (!Arrays.equals(expected, actual)) {
      fail("REST JSON wire format changed on byte level: " + fixture);
    }
  }

  public static void assertMatches(String fixture, String actual) throws IOException {
    assertMatches(fixture, actual.getBytes(StandardCharsets.UTF_8));
  }

}
