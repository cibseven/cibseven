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
package org.cibseven.bpm.qa.rolling.update.variable;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.runtime.VariableInstanceQuery;
import org.cibseven.bpm.qa.rolling.update.AbstractRollingUpdateTestCase;
import org.cibseven.bpm.qa.upgrade.ScenarioUnderTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This test ensures that the old engine can read an empty String variable created by the new engine.
 * Note: this test class needs to be adjusted after 7.15.0, since the behavior is fixed in 7.15.0
 * and therefore will work in rolling updates from there on
 *
 */
@ScenarioUnderTest("EmptyStringVariableScenario")
public class EmptyStringVariableTest extends AbstractRollingUpdateTestCase {

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.1")
  public void shouldFindEmptyStringVariableWithValue(String tag) {
    init(tag);
    //given
    VariableInstance variableInstance = rule.getRuntimeService().createVariableInstanceQuery()
        .variableName("myStringVar")
        .singleResult();

    // then
    assertThat(variableInstance.getValue()).isEqualTo("");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.1")
  public void shouldQueryEmptyStringVariableWithValueEquals(String tag) {
    init(tag);
    //given
    VariableInstanceQuery variableInstanceQuery = rule.getRuntimeService().createVariableInstanceQuery()
        .variableValueEquals("myStringVar", "");

    // then
    assertThat(variableInstanceQuery.count()).isEqualTo(1L);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("data")
  @ScenarioUnderTest("init.1")
  public void shouldQueryEmptyStringVariableWithValueNotEquals(String tag) {
    init(tag);
    //given
    VariableInstanceQuery variableInstanceQuery = rule.getRuntimeService().createVariableInstanceQuery()
        .variableValueNotEquals("myStringVar", "");

    // then
    assertThat(variableInstanceQuery.count()).isEqualTo(0L);
  }

}