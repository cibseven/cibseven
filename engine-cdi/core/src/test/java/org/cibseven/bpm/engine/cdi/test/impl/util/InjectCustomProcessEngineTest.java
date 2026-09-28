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
package org.cibseven.bpm.engine.cdi.test.impl.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.container.RuntimeContainerDelegate;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.cibseven.bpm.engine.cdi.test.CdiProcessEngineTestCase;
import org.cibseven.bpm.engine.cdi.test.impl.beans.InjectedProcessEngineBean;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.junit.jupiter.api.Test;

/**
 * @author Christopher Zell <christopher.zell@camunda.com>
 *
 * Engine registration/unregistration is done inside the test method with try/finally
 * so it does not interfere with the Quarkus-managed engine lifecycle (Arc container).
 */
public class InjectCustomProcessEngineTest extends CdiProcessEngineTestCase {

  @Test
  public void testProcessEngineInject() {
    ProcessEngine previousDefault = BpmPlatform.getProcessEngineService().getDefaultProcessEngine();
    ProcessEngine customEngine = TestHelper.getProcessEngine("org/cibseven/bpm/engine/cdi/test/impl/util/camunda.cfg.xml");

    try {
      if (previousDefault != null) {
        RuntimeContainerDelegate.INSTANCE.get().unregisterProcessEngine(previousDefault);
      }
      RuntimeContainerDelegate.INSTANCE.get().registerProcessEngine(customEngine);

      InjectedProcessEngineBean testClass = ProgrammaticBeanLookup.lookup(InjectedProcessEngineBean.class);
      assertNotNull(testClass);
      assertEquals("myCustomEngine", testClass.processEngine.getName());
    } finally {
      RuntimeContainerDelegate.INSTANCE.get().unregisterProcessEngine(customEngine);
      if (previousDefault != null) {
        RuntimeContainerDelegate.INSTANCE.get().registerProcessEngine(previousDefault);
      }
    }
  }
}