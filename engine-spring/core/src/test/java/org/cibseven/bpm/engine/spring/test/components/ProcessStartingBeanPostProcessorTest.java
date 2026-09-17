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
package org.cibseven.bpm.engine.spring.test.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.logging.Logger;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * @author Josh Long
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration("classpath:org/cibseven/bpm/engine/spring/test/components/ProcessStartingBeanPostProcessorTest-context.xml")
public class ProcessStartingBeanPostProcessorTest {

	private Logger log = Logger.getLogger(getClass().getName());

	@Autowired
	private ProcessEngine processEngine;

	@Autowired
	private ProcessInitiatingPojo processInitiatingPojo;

	@Autowired
	private RepositoryService repositoryService;

	@BeforeEach
	public void before() {
	  repositoryService.createDeployment()
	    .addClasspathResource("org/cibseven/bpm/engine/spring/test/autodeployment/autodeploy.b.bpmn20.xml")
	    .addClasspathResource("org/cibseven/bpm/engine/spring/test/components/waiter.bpmn20.xml")
	    .deploy();
	}

	@AfterEach
  public void after() {
    for (Deployment deployment : repositoryService.createDeploymentQuery().list()) {
      repositoryService.deleteDeployment(deployment.getId(), true);
    }
    processEngine.close();
    processEngine = null;
    processInitiatingPojo = null;
    repositoryService = null;
  }

	@Test
	public void testReturnedProcessInstance() throws Throwable {
		String processInstanceId = this.processInitiatingPojo.startProcessA(22);
		assertNotNull(processInstanceId, "the process instance id should not be null");
	}

	@Test
	public void testReflectingSideEffects() throws Throwable {
		assertNotNull(this.processInitiatingPojo, "the processInitiatingPojo mustn't be null.");

		this.processInitiatingPojo.reset();

		assertEquals(this.processInitiatingPojo.getMethodState(), 0);

		this.processInitiatingPojo.startProcess(53);

		assertEquals(this.processInitiatingPojo.getMethodState(), 1);
	}

	@Test
	public void testUsingBusinessKey() throws Throwable {
		long id = 5;
		String businessKey = "usersKey" + System.currentTimeMillis();
		ProcessInstance pi = processInitiatingPojo.enrollCustomer(businessKey, id);
		assertEquals(businessKey, pi.getBusinessKey(), "the business key of the resultant ProcessInstance should match " +
            "the one specified through the AOP-intercepted method" );

	}

	@Test
	public void testLaunchingProcessInstance() {
		long id = 343;
		String processInstance = processInitiatingPojo.startProcessA(id);
		Long customerId = (Long) processEngine.getRuntimeService().getVariable(processInstance, "customerId");
		assertEquals(customerId, (Long) id, "the process variable should both exist and be equal to the value given, " + id);
		log.info("the customerId from the ProcessInstance is " + customerId);
		assertNotNull(processInstance, "processInstanc can't be null");
		assertNotNull(customerId, "the variable should be non-null");
	}
}