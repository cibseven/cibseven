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

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.container.RuntimeContainerDelegate;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.cibseven.bpm.engine.cdi.test.CdiProcessEngineTestCase;
import org.cibseven.bpm.engine.cdi.test.impl.beans.InjectedProcessEngineBean;
import org.cibseven.bpm.engine.impl.test.TestHelper;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ExtendWith(ArquillianExtension.class)
public class InjectCustomProcessEngineTest extends CdiProcessEngineTestCase {

  protected ProcessEngine defaultProcessEngine = null;
  protected ProcessEngine processEngine = null;

  @BeforeEach
  public void init() {
    processEngine = TestHelper.getProcessEngine("org/cibseven/bpm/engine/cdi/test/impl/util/camunda.cfg.xml");
    defaultProcessEngine = BpmPlatform.getProcessEngineService().getDefaultProcessEngine();

    if (defaultProcessEngine != null) {
      RuntimeContainerDelegate.INSTANCE.get().unregisterProcessEngine(defaultProcessEngine);
    }

    RuntimeContainerDelegate.INSTANCE.get().registerProcessEngine(processEngine);
  }

  @AfterEach
  public void tearDownCdiProcessEngineTestCase() {
    RuntimeContainerDelegate.INSTANCE.get().unregisterProcessEngine(processEngine);

    if (defaultProcessEngine != null) {
      RuntimeContainerDelegate.INSTANCE.get().registerProcessEngine(defaultProcessEngine);
    }
  }

  @Test
  public void testProcessEngineInject() {
    //given only custom engine exist

    //when TestClass is created
    InjectedProcessEngineBean testClass = ProgrammaticBeanLookup.lookup(InjectedProcessEngineBean.class);
    Assert.assertNotNull(testClass);

    //then custom engine is injected
    Assert.assertEquals("myCustomEngine", testClass.processEngine.getName());
  }
}
