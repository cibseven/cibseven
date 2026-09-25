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

package org.cibseven.bpm.engine.impl.migration.validation.instance;

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingActivityInstance;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingProcessInstance;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;

/**
 * Explains why an instance standing inside an ad hoc sub process cannot be migrated (CIB7-1857).
 *
 * <p>{@link SupportedActivityInstanceValidator} already refuses it, because
 * {@link AdHocSubProcessActivityBehavior} is deliberately absent from the supported list. What it
 * cannot do is say which element is at fault: its message is generic by design, and a report reading
 * "the type of the source activity is not supported" leaves the reader to find the ad hoc scope
 * themselves. This adds the sentence that names it.
 *
 * <p>The refusal stays where it is rather than moving to plan validation, and that is a decision
 * rather than an omission. A migration plan is written against definitions; whether an instance is
 * standing inside the scope is a fact about the instance. Refusing the plan would also refuse
 * migrations that work perfectly well today -- an instance nowhere near the ad hoc scope, migrated
 * under a plan that happens to map the scope -- and tightening a refusal is a breaking change where
 * relaxing one is additive.
 *
 * <p>Why the refusal exists at all: the scope carries its state -- whether it has activated
 * anything, and whether its condition has held -- on a marker execution, and migration has no
 * handling for it. Supporting migration means first deciding what happens to that state, which is
 * why adding the behaviour to the supported list would quietly lose it.
 */
public class AdHocSubProcessInstanceValidator implements MigratingActivityInstanceValidator {

  @Override
  public void validate(MigratingActivityInstance migratingInstance, MigratingProcessInstance migratingProcessInstance,
      MigratingActivityInstanceValidationReportImpl instanceReport) {
    ScopeImpl sourceScope = migratingInstance.getSourceScope();
    if (sourceScope == sourceScope.getProcessDefinition()) {
      return;
    }

    ActivityImpl sourceActivity = (ActivityImpl) sourceScope;
    if (sourceActivity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior) {
      instanceReport.addFailure("Ad hoc sub process '" + sourceActivity.getId()
          + "' cannot be migrated while it is active, because the state it keeps about what it has"
          + " started has no defined migration. Complete or cancel the scope before migrating the"
          + " instance.");
    }
  }
}
