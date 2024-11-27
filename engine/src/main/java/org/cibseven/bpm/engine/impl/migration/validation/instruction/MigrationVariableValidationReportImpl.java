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
package org.cibseven.bpm.engine.impl.migration.validation.instruction;

import org.cibseven.bpm.engine.migration.MigrationVariableValidationReport;
import org.cibseven.bpm.engine.variable.value.TypedValue;

import java.util.ArrayList;
import java.util.List;

public class MigrationVariableValidationReportImpl implements MigrationVariableValidationReport {

  protected TypedValue typedValue;

  protected List<String> failures = new ArrayList<>();

  public MigrationVariableValidationReportImpl(TypedValue typedValue) {
    this.typedValue = typedValue;
  }

  @Override
  public <T extends TypedValue> T getTypedValue() {
    return (T) typedValue;
  }

  public void addFailure(String failure) {
    failures.add(failure);
  }

  public boolean hasFailures() {
    return !failures.isEmpty();
  }

  public List<String> getFailures() {
    return failures;
  }

  public String toString() {
    return "MigrationVariableValidationReportImpl{" +
      ", typedValue=" + typedValue +
      ", failures=" + failures +
      '}';
  }

}