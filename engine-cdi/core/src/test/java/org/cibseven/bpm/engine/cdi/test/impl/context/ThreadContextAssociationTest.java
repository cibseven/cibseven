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
package org.cibseven.bpm.engine.cdi.test.impl.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.cibseven.bpm.engine.cdi.test.CdiProcessEngineTestCase;
import org.cibseven.bpm.engine.cdi.test.impl.beans.ProcessScopedMessageBean;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jboss.arquillian.junit5.ArquillianExtension;

/**
 * 
 * @author Daniel Meyer
 */
@ExtendWith(ArquillianExtension.class)
public class ThreadContextAssociationTest extends CdiProcessEngineTestCase {
  
  @Test
  @Deployment
  public void testBusinessProcessScopedWithJobExecutor() throws InterruptedException {
    String pid = runtimeService.startProcessInstanceByKey("processkey").getId();
        
    waitForJobExecutorToProcessAllJobs(5000l, 25l);
        
    assertNull(managementService.createJobQuery().singleResult());
    
    ProcessScopedMessageBean messageBean = (ProcessScopedMessageBean) runtimeService.getVariable(pid, "processScopedMessageBean");
    assertEquals("Greetings from Berlin", messageBean.getMessage());
    
    runtimeService.signal(pid);
    
  }

}
