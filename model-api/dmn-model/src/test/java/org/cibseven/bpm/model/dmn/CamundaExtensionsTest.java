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
package org.cibseven.bpm.model.dmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.cibseven.bpm.model.dmn.instance.Decision;
import org.cibseven.bpm.model.dmn.instance.Input;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CamundaExtensionsTest {

  public static Stream<Arguments> parameters() {
    return Stream.of(new Object[][]{
         {Dmn.readModelFromStream(CamundaExtensionsTest.class.getResourceAsStream("CamundaExtensionsTest.dmn"))},
         // for compatibility reasons we gotta check the old namespace, too
         {Dmn.readModelFromStream(CamundaExtensionsTest.class.getResourceAsStream("CamundaExtensionsCompatibilityTest.dmn"))}
     }).map(Arguments::of);
   }


  @ParameterizedTest
  @MethodSource("parameters")
  public void testCamundaClauseOutput(DmnModelInstance modelInstanceParam) {
    DmnModelInstance modelInstance = modelInstanceParam.clone();
    Input input = modelInstance.getModelElementById("input");
    assertThat(input.getCamundaInputVariable()).isEqualTo("myVariable");
    input.setCamundaInputVariable("foo");
    assertThat(input.getCamundaInputVariable()).isEqualTo("foo");
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void testCamundaHistoryTimeToLive(DmnModelInstance modelInstanceParam) {
    DmnModelInstance modelInstance = modelInstanceParam.clone();
    Decision decision = modelInstance.getModelElementById("decision");
    assertThat(decision.getCamundaHistoryTimeToLive()).isEqualTo(5);
    decision.setCamundaHistoryTimeToLive(6);
    assertThat(decision.getCamundaHistoryTimeToLive()).isEqualTo(6);
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void testCamundaVersionTag(DmnModelInstance modelInstanceParam) {
    DmnModelInstance modelInstance = modelInstanceParam.clone();
    Decision decision = modelInstance.getModelElementById("decision");
    assertThat(decision.getVersionTag()).isEqualTo("1.0.0");
    decision.setVersionTag("1.1.0");
    assertThat(decision.getVersionTag()).isEqualTo("1.1.0");
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void validateModel(DmnModelInstance modelInstanceParam) {
    DmnModelInstance modelInstance = modelInstanceParam.clone();
    Dmn.validateModel(modelInstance);
  }

}
