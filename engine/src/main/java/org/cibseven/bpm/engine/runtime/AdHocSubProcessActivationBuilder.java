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
package org.cibseven.bpm.engine.runtime;

import java.util.List;
import java.util.Map;

/**
 * Starts activities of an ad hoc sub process, with variables belonging to each performance.
 *
 * <p>The specification allows an activity to be performed more than once, and the two performances
 * are not obliged to be given the same arguments. That is what this builder expresses and what
 * {@link RuntimeService#activateAdHocSubProcessActivities(String, java.util.Collection, Map)} cannot:
 * there the variables are keyed by activity id, so naming one activity twice in a single call leaves
 * one entry in the map and both performances take it.
 *
 * <pre>
 * runtimeService.createAdHocSubProcessActivation(scopeExecutionId)
 *     .startActivity("search").setVariable("query", "invoices 2026")
 *     .startActivity("search").setVariable("query", "credit notes 2026")
 *     .execute();
 * </pre>
 *
 * <p>Variables set after a {@code startActivity} belong to that performance. They are local to the
 * activity that is started, as they are for the older method.
 *
 * <p>Everything else is unchanged: the call is all-or-nothing, so one unstartable id starts none of
 * the others, and it returns the activity instance id of each performance in the order requested.
 */
public interface AdHocSubProcessActivationBuilder {

  /**
   * Adds a performance of the activity with the given id. Naming the same activity twice performs it
   * twice, which the specification explicitly permits.
   */
  AdHocSubProcessActivationBuilder startActivity(String activityId);

  /**
   * Sets one variable on the performance added last.
   *
   * @throws org.cibseven.bpm.engine.BadUserRequestException if no activity has been named yet
   */
  AdHocSubProcessActivationBuilder setVariable(String name, Object value);

  /**
   * Sets several variables on the performance added last. Null is ignored, so a caller can pass a map
   * it did not build itself without checking.
   *
   * @throws org.cibseven.bpm.engine.BadUserRequestException if no activity has been named yet
   */
  AdHocSubProcessActivationBuilder setVariables(Map<String, Object> variables);

  /**
   * Starts everything named, and returns the activity instance id of each performance in the order it
   * was named.
   *
   * <p>An id is null when the performance has not entered an activity instance in this transaction,
   * which is what an activity marked {@code camunda:asyncBefore} does: a job exists, an activity
   * instance does not yet.
   */
  List<String> execute();
}
