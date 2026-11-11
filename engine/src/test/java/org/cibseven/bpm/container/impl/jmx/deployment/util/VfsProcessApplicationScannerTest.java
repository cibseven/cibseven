/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.container.impl.jmx.deployment.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import org.cibseven.bpm.container.impl.deployment.scanning.ProcessApplicationScanningUtil;
import org.junit.jupiter.api.Test;


/**
 * @author Clint Manning
 */
public class VfsProcessApplicationScannerTest {

  @Test
  public void testScanProcessArchivePathForResources() throws MalformedURLException {

    // given: scanning the relative test resource root
    URLClassLoader classLoader = new URLClassLoader(new URL[]{new URL("file:")});
    String processRootPath = "classpath:org/cibseven/bpm/container/impl/jmx/deployment/process/";
    Map<String, byte[]> scanResult = ProcessApplicationScanningUtil.findResources(classLoader, processRootPath, null);

    // expect: finds only the BPMN process file and not treats the 'bpmn' folder
    assertEquals(1, scanResult.size());
    String processFileName = "VfsProcessScannerTest.bpmn20.xml";
    assertTrue(contains(scanResult, processFileName), "'" + processFileName + "'not found");
    assertFalse(contains(scanResult, "processResource.txt"), "'bpmn' folder in resource path found");
  }

  @Test
  public void testScanProcessArchivePathForCmmnResources() throws MalformedURLException {

    // given: scanning the relative test resource root
    URLClassLoader classLoader = new URLClassLoader(new URL[]{new URL("file:")});
    String processRootPath = "classpath:org/cibseven/bpm/container/impl/jmx/deployment/case/";
    Map<String, byte[]> scanResult = ProcessApplicationScanningUtil.findResources(classLoader, processRootPath, null);

    // expect: finds only the CMMN process file and not treats the 'cmmn' folder
    assertEquals(1, scanResult.size());
    String processFileName = "VfsProcessScannerTest.cmmn";
    assertTrue(contains(scanResult, processFileName), "'" + processFileName + "' not found");
    assertFalse(contains(scanResult, "caseResource.txt"), "'cmmn' in resource path found");
  }

  @Test
  public void testScanProcessArchivePathWithAdditionalResourceSuffixes() throws MalformedURLException {
    URLClassLoader classLoader = new URLClassLoader(new URL[]{new URL("file:")});
    String processRootPath = "classpath:org/cibseven/bpm/container/impl/jmx/deployment/script/";
    String[] additionalResourceSuffixes = new String[] { "py", "groovy", "rb" };
    Map<String, byte[]> scanResult = ProcessApplicationScanningUtil.findResources(classLoader, processRootPath, null, additionalResourceSuffixes);

    assertEquals(4, scanResult.size());
    String processFileName = "VfsProcessScannerTest.bpmn20.xml";
    assertTrue(contains(scanResult, processFileName), "'" + processFileName + "' not found");
    assertTrue(contains(scanResult, "hello.py"), "'hello.py' in resource path found");
    assertTrue(contains(scanResult, "hello.rb"), "'hello.rb' in resource path found");
    assertTrue(contains(scanResult, "hello.groovy"), "'hello.groovy' in resource path found");
  }

  private boolean contains(Map<String, byte[]> scanResult, String suffix) {
    for (String string : scanResult.keySet()) {
      if (string.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }
}
