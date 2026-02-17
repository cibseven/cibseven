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
package org.cibseven.bpm.engine.cdi.test.api.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;

import org.cibseven.bpm.engine.cdi.BusinessProcess;
import org.cibseven.bpm.engine.cdi.annotation.ExecutionIdLiteral;
import org.cibseven.bpm.engine.cdi.test.CdiProcessEngineTestCase;
import org.cibseven.bpm.engine.test.Deployment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jboss.arquillian.junit5.ArquillianExtension;

/**
 * 
 * @author Daniel Meyer
 */
@ExtendWith(ArquillianExtension.class)
public class ExecutionIdTest extends CdiProcessEngineTestCase {
  
  @Test
  @Deployment
  public void testExecutionIdInjectableByName() {
    getBeanInstance(BusinessProcess.class).startProcessByKey("keyOfTheProcess");
    String processInstanceId = (String) getBeanInstance("processInstanceId");
    Assertions.assertNotNull(processInstanceId);
    String executionId = (String) getBeanInstance("executionId");
    Assertions.assertNotNull(executionId);
    
    assertEquals(processInstanceId, executionId);
  }
  
  @Test
  @Deployment
  public void testExecutionIdInjectableByQualifier() {
    getBeanInstance(BusinessProcess.class).startProcessByKey("keyOfTheProcess");
    
    Set<Bean<?>> beans = beanManager.getBeans(String.class, new ExecutionIdLiteral());    
    Bean<String> bean = (Bean<java.lang.String>) beanManager.resolve(beans);
    
    CreationalContext<String> ctx = beanManager.createCreationalContext(bean);
    String executionId = (String) beanManager.getReference(bean, String.class, ctx);   
    Assertions.assertNotNull(executionId);
    
    String processInstanceId = (String) getBeanInstance("processInstanceId");
    Assertions.assertNotNull(processInstanceId);
    
    assertEquals(processInstanceId, executionId);
  }
  
}
