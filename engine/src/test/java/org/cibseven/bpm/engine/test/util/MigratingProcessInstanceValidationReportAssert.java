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
package org.cibseven.bpm.engine.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.cibseven.bpm.engine.migration.MigratingActivityInstanceValidationReport;
import org.cibseven.bpm.engine.migration.MigratingProcessInstanceValidationReport;
import org.cibseven.bpm.engine.migration.MigratingTransitionInstanceValidationReport;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;

public class MigratingProcessInstanceValidationReportAssert {

  protected MigratingProcessInstanceValidationReport actual;

  public MigratingProcessInstanceValidationReportAssert(MigratingProcessInstanceValidationReport report) {
    this.actual = report;
  }

  public MigratingProcessInstanceValidationReportAssert isNotNull() {
    assertNotNull(actual, "Expected report to be not null");

    return this;
  }

  public MigratingProcessInstanceValidationReportAssert hasProcessInstance(ProcessInstance processInstance) {
    return hasProcessInstanceId(processInstance.getId());
  }

  public MigratingProcessInstanceValidationReportAssert hasProcessInstanceId(String processInstanceId) {
    isNotNull();

    assertEquals(processInstanceId, actual.getProcessInstanceId(), "Expected report to be for process instance");

    return this;
  }

  public MigratingProcessInstanceValidationReportAssert hasFailures(String... expectedFailures) {
    isNotNull();

    List<String> actualFailures = actual.getFailures();

    Collection<Matcher<? super String>> matchers = new ArrayList<Matcher<? super String>>();
    for (String expectedFailure : expectedFailures) {
      matchers.add(Matchers.containsString(expectedFailure));
    }

    MatcherAssertions.assertThat("Expected failures:\n" + joinFailures(Arrays.asList(expectedFailures)) +
        "But found failures:\n" + joinFailures(actualFailures),
      actualFailures, Matchers.containsInAnyOrder(matchers));

    return this;
  }

  public MigratingProcessInstanceValidationReportAssert hasActivityInstanceFailures(String sourceScopeId, String... expectedFailures) {
    isNotNull();

    MigratingActivityInstanceValidationReport actualReport = null;
    for (MigratingActivityInstanceValidationReport instanceReport : actual.getActivityInstanceReports()) {
      if (sourceScopeId.equals(instanceReport.getSourceScopeId())) {
        actualReport = instanceReport;
        break;
      }
    }

    assertNotNull(actualReport, "No validation report found for source scope: " + sourceScopeId);

    assertFailures(sourceScopeId, Arrays.asList(expectedFailures), actualReport.getFailures());

    return this;
  }

  public MigratingProcessInstanceValidationReportAssert hasTransitionInstanceFailures(String sourceScopeId, String... expectedFailures) {
    isNotNull();

    MigratingTransitionInstanceValidationReport actualReport = null;
    for (MigratingTransitionInstanceValidationReport instanceReport : actual.getTransitionInstanceReports()) {
      if (sourceScopeId.equals(instanceReport.getSourceScopeId())) {
        actualReport = instanceReport;
        break;
      }
    }

    assertNotNull(actualReport, "No validation report found for source scope: " + sourceScopeId);

    assertFailures(sourceScopeId, Arrays.asList(expectedFailures), actualReport.getFailures());

    return this;
  }

  protected void assertFailures(String sourceScopeId, List<String> expectedFailures, List<String> actualFailures) {

    Collection<Matcher<? super String>> matchers = new ArrayList<Matcher<? super String>>();
    for (String expectedFailure : expectedFailures) {
      matchers.add(Matchers.containsString(expectedFailure));
    }

    MatcherAssertions.assertThat("Expected failures for source scope: " + sourceScopeId + "\n" + joinFailures(expectedFailures) +
        "But found failures:\n" + joinFailures(actualFailures),
      actualFailures, Matchers.containsInAnyOrder(matchers));
  }

  public static MigratingProcessInstanceValidationReportAssert assertThat(MigratingProcessInstanceValidationReport report) {
    return new MigratingProcessInstanceValidationReportAssert(report);
  }

  public String joinFailures(List<String> failures) {
    StringBuilder builder = new StringBuilder();
    for (Object failure : failures) {
      builder.append("\t\t").append(failure).append("\n");
    }

    return builder.toString();
  }



}