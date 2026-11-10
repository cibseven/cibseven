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
package org.cibseven.bpm.integrationtest.functional.classloading.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <p>{@link Variables#fileValue(File)} detects the MIME type with
 * {@code jakarta.activation.MimetypesFileTypeMap}, which finds its implementation (Angus) through
 * the context class loader. The detection must work whether the context class loader is the
 * application, the cibseven-commons-typed-values module or the engine module. On WildFly, the
 * two modules see Angus only through their module dependencies (CIB7-2605).</p>
 */
@ExtendWith(ArquillianExtension.class)
public class FileValueMimeTypeTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive createDeployment() {
    return initWebArchiveDeployment();
  }

  @Test
  public void shouldDetectMimeTypeWithApplicationAsContextClassLoader() throws IOException {
    assertMimeTypesDetected(Thread.currentThread().getContextClassLoader());
  }

  @Test
  public void shouldDetectMimeTypeWithTypedValuesAsContextClassLoader() throws IOException {
    assertMimeTypesDetected(Variables.class.getClassLoader());
  }

  @Test
  public void shouldDetectMimeTypeWithEngineAsContextClassLoader() throws IOException {
    assertMimeTypesDetected(ProcessEngine.class.getClassLoader());
  }

  protected void assertMimeTypesDetected(ClassLoader contextClassLoader) throws IOException {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(contextClassLoader);
    try {
      assertEquals("text/plain", detectMimeType(".txt"));
      assertEquals("image/png", detectMimeType(".png"));
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  protected String detectMimeType(String suffix) throws IOException {
    Path file = Files.createTempFile("cibseven-mime-type", suffix);
    try {
      return Variables.fileValue(file.toFile()).getMimeType();
    } finally {
      Files.delete(file);
    }
  }

}
