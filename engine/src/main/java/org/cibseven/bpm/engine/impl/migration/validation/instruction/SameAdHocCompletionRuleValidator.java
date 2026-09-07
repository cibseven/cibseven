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
package org.cibseven.bpm.engine.impl.migration.validation.instruction;

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocSubProcessActivityBehavior;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;

/**
 * Refuses migrating an ad hoc sub process onto one whose completion rule is different.
 *
 * <p>An ad hoc scope completes one of two ways. With a {@code completionCondition} the condition
 * decides. Without one it completes once nothing is active and at least one child has been
 * activated, which the engine tracks in a counter on the scope execution.
 *
 * <p>No migration instruction describes that counter, so mapping between the two rules changes
 * silently when the instance ends. A scope that has activated three children and is waiting for a
 * fourth, migrated to a target with a completion condition, stops being governed by the count that
 * was accumulated for it; a scope governed by a condition, migrated to a target without one, starts
 * being governed by a count that was never meant to control anything and completes the moment its
 * last child ends.
 *
 * <p>Changing only the condition's <em>expression</em> is allowed. Re-reading an expression from the
 * target definition is ordinary migration behaviour, and the rule that decides completion is
 * unchanged. Changing {@code cancelRemainingInstances} is allowed for the same reason: it alters
 * what happens at completion, not what completion means.
 *
 *  * <p>A third rule was added later: a scope marked {@code explicitCompletionOnly} ends only on a
 *  * completion request. Migrating between it and either of the other two is refused for the same
 *  * reason as above — a parked instance mapped onto an auto-completing target would end the moment
 *  * its last child does, and an auto-completing instance mapped onto a parked target would wait for
 *  * a completion request that whoever started it never intended to send.
 */
public class SameAdHocCompletionRuleValidator implements MigrationInstructionValidator {

  @Override
  public void validate(ValidatingMigrationInstruction instruction, ValidatingMigrationInstructions instructions,
      MigrationInstructionValidationReportImpl report) {
    ActivityImpl sourceActivity = instruction.getSourceActivity();
    ActivityImpl targetActivity = instruction.getTargetActivity();

    if (!isAdHocSubProcess(sourceActivity) || !isAdHocSubProcess(targetActivity)) {
      // A mapping between an ad hoc scope and anything else is already refused by
      // SameBehaviorInstructionValidator, which reports the incompatible types.
      return;
    }

    boolean sourceHasCondition = hasCompletionCondition(sourceActivity);
    if (sourceHasCondition != hasCompletionCondition(targetActivity)) {
      report.addFailure("Cannot migrate an ad hoc sub process to one with a different completion"
          + " rule (the source " + (sourceHasCondition ? "has" : "has no")
          + " completion condition and the target " + (sourceHasCondition ? "has none" : "has one")
          + ")");
      return;
    }

    boolean sourceIsExplicitOnly = isExplicitCompletionOnly(sourceActivity);
    if (sourceIsExplicitOnly != isExplicitCompletionOnly(targetActivity)) {
      report.addFailure("Cannot migrate an ad hoc sub process to one with a different completion"
          + " rule (the source " + (sourceIsExplicitOnly ? "ends" : "does not end")
          + " only on an explicit completion request and the target "
          + (sourceIsExplicitOnly ? "does not" : "does") + ")");
      return;
    }

    String sourceDriver = driverActivityId(sourceActivity);
    String targetDriver = driverActivityId(targetActivity);
    if (sourceDriver == null ? targetDriver != null : !sourceDriver.equals(targetDriver)) {
      report.addFailure("Cannot migrate an ad hoc sub process to one with a different driver"
          + " activity (the source has "
          + (sourceDriver == null ? "none" : "'" + sourceDriver + "'") + " and the target has "
          + (targetDriver == null ? "none" : "'" + targetDriver + "'") + ")");
    }
  }

  protected String driverActivityId(ActivityImpl activity) {
    return ((AdHocSubProcessActivityBehavior) activity.getActivityBehavior())
        .getDriverActivityId();
  }

  protected boolean isExplicitCompletionOnly(ActivityImpl activity) {
    return ((AdHocSubProcessActivityBehavior) activity.getActivityBehavior())
        .isExplicitCompletionOnly();
  }

  protected boolean isAdHocSubProcess(ActivityImpl activity) {
    return activity.getActivityBehavior() instanceof AdHocSubProcessActivityBehavior;
  }

  protected boolean hasCompletionCondition(ActivityImpl activity) {
    return ((AdHocSubProcessActivityBehavior) activity.getActivityBehavior()).hasCompletionCondition();
  }

}
