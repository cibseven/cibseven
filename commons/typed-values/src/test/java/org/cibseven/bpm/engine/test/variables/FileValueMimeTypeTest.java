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
package org.cibseven.bpm.engine.test.variables;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.FileValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Variables#fileValue(File)} detects the MIME type from the file name with
 * {@code jakarta.activation.MimetypesFileTypeMap}. The Jakarta Activation API has no
 * implementation of its own: without Eclipse Angus it answers {@code application/octet-stream}
 * for every file, and that type is stored with the variable (CIB7-2605).
 *
 * <p>The test belongs to this module: its test classpath gets Angus only through the runtime
 * dependency of cibseven-commons-typed-values. The engine and the External Task Client get Angus
 * through other test dependencies as well, which hid the bug.</p>
 */
public class FileValueMimeTypeTest {

  @TempDir
  Path tempDir;

  @ParameterizedTest
  @CsvSource({
      "simpleFile.txt, text/plain",
      "image.png,      image/png",
      "page.html,      text/html",
      "photo.jpg,      image/jpeg"
  })
  public void shouldDetectMimeTypeOfFile(String fileName, String expectedMimeType) throws IOException {
    File file = Files.createFile(tempDir.resolve(fileName)).toFile();

    FileValue fileValue = Variables.fileValue(file);

    assertThat(fileValue.getFilename()).isEqualTo(fileName);
    assertThat(fileValue.getMimeType()).isEqualTo(expectedMimeType);
  }

  @ParameterizedTest
  @CsvSource({
      "simpleFile.txt, text/plain",
      "image.png,      image/png"
  })
  public void shouldDetectMimeTypeOfTransientFile(String fileName, String expectedMimeType) throws IOException {
    File file = Files.createFile(tempDir.resolve(fileName)).toFile();

    FileValue fileValue = Variables.fileValue(file, true);

    assertThat(fileValue.isTransient()).isTrue();
    assertThat(fileValue.getMimeType()).isEqualTo(expectedMimeType);
  }

  @Test
  public void shouldFallBackToOctetStreamForUnknownExtension() throws IOException {
    File file = Files.createFile(tempDir.resolve("data.cibseven-unknown")).toFile();

    FileValue fileValue = Variables.fileValue(file);

    assertThat(fileValue.getMimeType()).isEqualTo("application/octet-stream");
  }

}
