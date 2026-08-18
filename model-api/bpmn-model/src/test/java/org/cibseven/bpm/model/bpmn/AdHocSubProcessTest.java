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
package org.cibseven.bpm.model.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.CompletionCondition;
import org.cibseven.bpm.model.bpmn.instance.Definitions;
import org.cibseven.bpm.model.bpmn.instance.Process;
import org.junit.Before;
import org.junit.Test;

public class AdHocSubProcessTest {

  protected BpmnModelInstance modelInstance;
  protected Process process;

  @Before
  public void createEmptyModel() {
    modelInstance = Bpmn.createEmptyModel();
    Definitions definitions = modelInstance.newInstance(Definitions.class);
    definitions.setTargetNamespace("http://cibseven.org/examples");
    modelInstance.setDefinitions(definitions);

    process = modelInstance.newInstance(Process.class);
    process.setAttributeValue("id", "process", true);
    definitions.addChildElement(process);
  }

  protected AdHocSubProcess newAdHocSubProcess() {
    AdHocSubProcess adHocSubProcess = modelInstance.newInstance(AdHocSubProcess.class);
    adHocSubProcess.setAttributeValue("id", "adHoc", true);
    process.addChildElement(adHocSubProcess);
    return adHocSubProcess;
  }

  @Test
  public void shouldDefaultOrderingToParallel() {
    assertThat(newAdHocSubProcess().getOrdering()).isEqualTo(AdHocOrdering.Parallel);
  }

  @Test
  public void shouldDefaultCancelRemainingInstancesToTrue() {
    assertThat(newAdHocSubProcess().isCancelRemainingInstances()).isTrue();
  }

  @Test
  public void shouldRoundTripOrdering() {
    AdHocSubProcess adHocSubProcess = newAdHocSubProcess();
    adHocSubProcess.setOrdering(AdHocOrdering.Sequential);
    assertThat(adHocSubProcess.getOrdering()).isEqualTo(AdHocOrdering.Sequential);
  }

  @Test
  public void shouldRoundTripCancelRemainingInstances() {
    AdHocSubProcess adHocSubProcess = newAdHocSubProcess();
    adHocSubProcess.setCancelRemainingInstances(false);
    assertThat(adHocSubProcess.isCancelRemainingInstances()).isFalse();
  }

  @Test
  public void shouldRoundTripCompletionCondition() {
    AdHocSubProcess adHocSubProcess = newAdHocSubProcess();
    assertThat(adHocSubProcess.getCompletionCondition()).isNull();

    CompletionCondition completionCondition = modelInstance.newInstance(CompletionCondition.class);
    completionCondition.setTextContent("${done}");
    adHocSubProcess.setCompletionCondition(completionCondition);

    assertThat(adHocSubProcess.getCompletionCondition()).isNotNull();
    assertThat(adHocSubProcess.getCompletionCondition().getTextContent()).isEqualTo("${done}");
  }
}
