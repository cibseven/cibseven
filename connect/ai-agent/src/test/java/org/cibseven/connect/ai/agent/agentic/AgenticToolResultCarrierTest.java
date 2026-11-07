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
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.junit.jupiter.api.Test;

/**
 * Keeping a tool's results for the agent never fails the end of that tool, which may be a person
 * completing a task (CIB7-2215, review F8).
 */
public class AgenticToolResultCarrierTest {

  @Test
  public void anExecutionOfAnotherKindIsLeftAlone() {
    assertThatCode(() -> new AgenticToolResultCarrier().notify(mock(DelegateExecution.class)))
        .doesNotThrowAnyException();
  }

  @Test
  public void aFailureWhileCarryingDoesNotFailTheEnd() {
    ExecutionEntity execution = mock(ExecutionEntity.class);
    when(execution.getParent()).thenThrow(new IllegalStateException("kaputt"));

    assertThatCode(() -> new AgenticToolResultCarrier().notify(execution))
        .doesNotThrowAnyException();
  }
}
