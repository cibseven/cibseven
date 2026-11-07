/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.runtime.Job;

/**
 * Runs a turn by hand: every step of it, each a job of its own (CIB7-2148), with the job executor
 * off so the outcome is a sequence and not a race.
 */
final class TurnSteps {

  /** More steps than any turn of these tests takes, so a loop that never ends fails, not hangs. */
  static final int MAX_STEPS = 60;

  private TurnSteps() {
  }

  /** Executes the instance's waiting jobs until none is left, and returns how many ran. */
  static int runToTheEnd(ProcessEngine engine, String processInstanceId) {
    int steps = 0;
    while (true) {
      List<Job> jobs = engine.getManagementService().createJobQuery()
          .processInstanceId(processInstanceId).list();
      if (jobs.isEmpty()) {
        return steps;
      }
      if (++steps > MAX_STEPS) {
        throw new AssertionError("more than " + MAX_STEPS + " steps, the turn does not end");
      }
      engine.getManagementService().executeJob(jobs.get(0).getId());
    }
  }
}
