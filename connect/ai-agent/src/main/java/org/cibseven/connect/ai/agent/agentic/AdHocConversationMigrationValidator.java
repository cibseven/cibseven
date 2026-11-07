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

import org.cibseven.bpm.engine.impl.migration.instance.MigratingActivityInstance;
import org.cibseven.bpm.engine.impl.migration.instance.MigratingProcessInstance;
import org.cibseven.bpm.engine.impl.migration.validation.instance.MigratingActivityInstanceValidationReportImpl;
import org.cibseven.bpm.engine.impl.migration.validation.instance.MigratingActivityInstanceValidator;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;

/**
 * Explains why an instance carrying a conversation holder (CIB7-2137) cannot be migrated, the
 * same way {@code AdHocSubProcessInstanceValidator} explains an active ad hoc scope (CIB7-1857).
 *
 * <p>A {@code perActivity} scope's holder is built to outlive the scope: that is the entire
 * point, a feedback loop's entries sharing one conversation. It survives exactly as long as the
 * execution enclosing the scope does, which migration has no defined handling for -- the holder
 * has no activity, so it is not a node of the {@code ActivityInstance} tree migration walks, and
 * its local variables are never claimed by anything migration does visit. Left unchecked, that
 * surfaces many steps later and far from the cause, as the engine's generic
 * {@code Process instance contains not migrated variables} failure naming only variable ids.
 *
 * <p>This runs for every migrating instance, including the process instance's own root scope,
 * where a holder sits when its scope is modelled directly in the process: unlike
 * {@code AdHocSubProcessInstanceValidator}, there is no reason to exempt the root here, since the
 * holder being checked is never itself the ad hoc scope.
 *
 * <p>Refusal is a temporary MVP restriction pending CIB7-2167's generic engine SPI for
 * activity-less dependent state. The engine must not know about AI-specific state. A root
 * holder blocks all migration for the rest of the process instance's lifetime, even after
 * its agentic scope was left for good.
 */
public class AdHocConversationMigrationValidator implements MigratingActivityInstanceValidator {

  @Override
  public void validate(MigratingActivityInstance migratingInstance,
      MigratingProcessInstance migratingProcessInstance,
      MigratingActivityInstanceValidationReportImpl instanceReport) {
    // A holder's parent is always a scope execution (AdHocConversation#enclosing resolves the
    // nearest flow-scope). Without this guard, a leaf activity instance sharing the same
    // compacted execution as its scope -- any non-scope activity directly in a sequential,
    // non-concurrent flow -- would report the very same holder a second time.
    if (!migratingInstance.getSourceScope().isScope()) {
      return;
    }
    ExecutionEntity representativeExecution = migratingInstance.resolveRepresentativeExecution();
    if (representativeExecution == null) {
      return;
    }
    for (PvmExecutionImpl candidate : representativeExecution.getEventScopeExecutions()) {
      String markerValue = AdHocConversation.activityId(candidate);
      if (markerValue == null) {
        continue;
      }
      instanceReport.addFailure("Ad hoc sub process '" + markerValue + "' keeps its conversation"
          + " across entries (" + AdHocConversation.MODE_PROPERTY + " = "
          + AdHocConversation.PER_ACTIVITY + "); its holder has no defined migration and survives"
          + " until " + enclosingDescription(migratingInstance.getSourceScope())
          + " ends, independently of whether '" + markerValue + "' itself is still active."
          + " Complete or cancel " + enclosingDescription(migratingInstance.getSourceScope())
          + " before migrating the instance.");
    }
  }

  /** How to name the scope the holder was found under, in words for the refusal. */
  protected static String enclosingDescription(ScopeImpl enclosingScope) {
    return (enclosingScope == null || enclosingScope == enclosingScope.getProcessDefinition())
        ? "the process instance"
        : "'" + enclosingScope.getId() + "'";
  }
}
