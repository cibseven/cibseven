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
package org.cibseven.bpm.model.bpmn.util;

import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.xml.impl.util.IoUtil;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.InputStream;

/**
 * JUnit 5 extension to parse BPMN model before each test method.
 */
public class ParseBpmnModelRule implements BeforeEachCallback {

  protected BpmnModelInstance bpmnModelInstance;

  @Override
  public void beforeEach(ExtensionContext context) {
    BpmnModelResource annotation = context.getRequiredTestMethod().getAnnotation(BpmnModelResource.class);
    if (annotation != null) {
      Class<?> testClass = context.getRequiredTestClass();
      String methodName = context.getRequiredTestMethod().getName();
      String resourceFolderName = testClass.getName().replaceAll("\\.", "/");
      String bpmnResourceName = resourceFolderName + "." + methodName + ".bpmn";
      InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream(bpmnResourceName);
      try {
        bpmnModelInstance = Bpmn.readModelFromStream(resourceAsStream);
      } finally {
        IoUtil.closeSilently(resourceAsStream);
      }
    }
  }

  public BpmnModelInstance getBpmnModel() {
    return bpmnModelInstance;
  }
}