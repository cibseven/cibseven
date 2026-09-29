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
package org.cibseven.bpm.qa.rolling.update.externalTask;

import java.util.List;
import org.cibseven.bpm.engine.externaltask.ExternalTask;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.cibseven.bpm.qa.rolling.update.AbstractRollingUpdateTestCase;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 *
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
@ScenarioUnderTest("ProcessWithExternalTaskScenario")
public class CompleteProcessWithExternalTaskTest extends AbstractRollingUpdateTestCase {

  public static final long LOCK_TIME = 5 * 60 * 1000;

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.1")
  public void testCompleteProcessWithExternalTask(String tag) throws InterruptedException {
    init(tag);
    //given process with external task
    String buisnessKey = rule.getBuisnessKey();
    List<LockedExternalTask> externalTasks = rule.getExternalTaskService().fetchAndLock(1, buisnessKey)
      .topic(buisnessKey, LOCK_TIME)
      .execute();
    assertEquals(1, externalTasks.size());

    //when external task is completed
    rule.getExternalTaskService().complete(externalTasks.get(0).getId(), buisnessKey);

    //then process instance is ended
    rule.assertScenarioEnded();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.fetch.1")
  public void testCompleteProcessWithFetchedExternalTask(String tag) throws InterruptedException {
    init(tag);
    //given process with locked external task
    String buisnessKey = rule.getBuisnessKey();
    ExternalTask task = rule.getExternalTaskService()
                            .createExternalTaskQuery()
                            .locked()
                            .topicName(buisnessKey)
                            .workerId(buisnessKey)
                            .singleResult();
    assertNotNull(task);

    //when external task is completed
    rule.getExternalTaskService().complete(task.getId(), buisnessKey);

    //then no locked external task with worker id exists
    task = rule.getExternalTaskService()
                            .createExternalTaskQuery()
                            .locked()
                            .topicName(buisnessKey)
                            .workerId(buisnessKey)
                            .singleResult();
    assertNull(task);
    rule.assertScenarioEnded();
  }
}
