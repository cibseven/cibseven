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
package org.cibseven.bpm.spring.boot.starter.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.spring.boot.starter.actuator.JobExecutorHealthIndicator.Details;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
public class JobExecutorHealthIndicatorTest {

  private static final String LOCK_OWNER = "lockowner";
  private static final int LOCK_TIME_IN_MILLIS = 5;
  private static final int MAX_JOBS_PER_ACQUISITION = 6;
  private static final String JOB_EXECUTOR_NAME = "job executor name";
  private static final int WAIT_TIME_IN_MILLIS = 7;
  private static final List<ProcessEngineImpl> PROCESS_ENGINES = new ArrayList<ProcessEngineImpl>();
  private static final String PROCESS_ENGINE_NAME = "process engine name";

  static {
    ProcessEngineImpl processEngineImpl = mock(ProcessEngineImpl.class);
    when(processEngineImpl.getName()).thenReturn(PROCESS_ENGINE_NAME);
    PROCESS_ENGINES.add(processEngineImpl);
  }

  @Mock
  private JobExecutor jobExecutor;

  @BeforeEach
  public void init() {
    lenient().when(jobExecutor.getLockOwner()).thenReturn(LOCK_OWNER);
    lenient().when(jobExecutor.getLockTimeInMillis()).thenReturn(LOCK_TIME_IN_MILLIS);
    lenient().when(jobExecutor.getMaxJobsPerAcquisition()).thenReturn(MAX_JOBS_PER_ACQUISITION);
    lenient().when(jobExecutor.getName()).thenReturn(JOB_EXECUTOR_NAME);
    lenient().when(jobExecutor.getWaitTimeInMillis()).thenReturn(WAIT_TIME_IN_MILLIS);
    lenient().when(jobExecutor.getProcessEngines()).thenReturn(PROCESS_ENGINES);
  }

  @Test
	public void nullTest() {
		assertThrows(NullPointerException.class, () -> {
			new JobExecutorHealthIndicator(null);
		});
	}

  @Test
  public void upTest() {
    when(jobExecutor.isActive()).thenReturn(true);
    JobExecutorHealthIndicator indicator = new JobExecutorHealthIndicator(jobExecutor);
    Health health = indicator.health();
    assertEquals(Status.UP, health.getStatus());
    assertDetails(health);
  }

  @Test
  public void downTest() {
    when(jobExecutor.isActive()).thenReturn(false);
    JobExecutorHealthIndicator indicator = new JobExecutorHealthIndicator(jobExecutor);
    Health health = indicator.health();
    assertEquals(Status.DOWN, health.getStatus());
    assertDetails(health);
  }

  private void assertDetails(Health health) {
    Details details = (Details) health.getDetails().get("jobExecutor");
    assertEquals(LOCK_OWNER, details.getLockOwner());
    assertEquals(LOCK_TIME_IN_MILLIS, details.getLockTimeInMillis());
    assertEquals(MAX_JOBS_PER_ACQUISITION, details.getMaxJobsPerAcquisition());
    assertEquals(JOB_EXECUTOR_NAME, details.getName());
    assertEquals(WAIT_TIME_IN_MILLIS, details.getWaitTimeInMillis());
    assertEquals(PROCESS_ENGINES.size(), details.getProcessEngineNames().size());
    assertEquals(PROCESS_ENGINE_NAME, details.getProcessEngineNames().iterator().next());
  }
}
