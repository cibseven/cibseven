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
package org.cibseven.bpm.engine.test.bpmn.adhoc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Which children of an ad hoc sub process may be started directly.
 *
 * <p>The rule is derived from BPMN 2.0.0 section 10.3.5, not enumerated: a child is startable if it
 * is an Activity and has no incoming sequence flow from within the scope. These tests pin the
 * derivation, so that a future activation command validating against this set is validating against
 * the specification rather than against a list.
 */
public class AdHocSubProcessStartableActivitiesTest extends PluggableProcessEngineTest {

  protected List<String> startableActivitiesOf(String processDefinitionKey, String adHocId) {
    ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey(processDefinitionKey).singleResult();
    ActivityImpl adHoc = ((ProcessDefinitionEntity) repositoryService
        .getProcessDefinition(definition.getId())).findActivity(adHocId);
    return adHoc.getProperties().get(BpmnProperties.AD_HOC_STARTABLE_ACTIVITIES);
  }

  /**
   * Activities are startable; a data object and an intermediate catch event are not, because the
   * specification lists them as MAY-be-used precisely because they are not Activities.
   */
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void mixedChildren() {
    assertThat(startableActivitiesOf("adHocMixedChildren", "adHoc"))
        .as("only the Activities, and a nested ad hoc scope is one")
        .containsExactlyInAnyOrder(
            "aUserTask", "aServiceTask", "anEmbeddedSubProcess", "aNestedAdHocScope",
            "aMultiInstanceTask");
  }

  /**
   * A compensation handler and an event sub process are Activities with no incoming flow, so the
   * rule would call them startable on shape alone. Both are wrong: one is reached by compensation
   * being thrown, the other by its own start event firing, and starting either directly would bypass
   * the mechanism that defines it.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessStartableActivitiesTest.mixedChildren.bpmn20.xml")
  @Test
  public void activitiesWithTheirOwnActivationMechanismAreNotStartable() {
    assertThat(startableActivitiesOf("adHocMixedChildren", "adHoc"))
        .as("compensation handlers and event sub processes are not started directly")
        .doesNotContain("aCompensationHandler", "anEventSubProcess");
  }

  /**
   * An activity with loop characteristics is still startable under its own id. The parser wraps it
   * in a generated multi-instance body, which is what the command has to start, but the id a caller
   * knows is the one in the model.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessStartableActivitiesTest.mixedChildren.bpmn20.xml")
  @Test
  public void aMultiInstanceChildIsStartableUnderItsOwnId() {
    assertThat(startableActivitiesOf("adHocMixedChildren", "adHoc"))
        .as("the model's id, not the generated body's")
        .contains("aMultiInstanceTask")
        .doesNotContain("aMultiInstanceTask#multiInstanceBody");
  }

  /**
   * A nested ad hoc scope computes its own set independently, and a grandchild belongs to the inner
   * scope rather than to the outer one.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessStartableActivitiesTest.mixedChildren.bpmn20.xml")
  @Test
  public void nestedScopeHasItsOwnSet() {
    assertThat(startableActivitiesOf("adHocMixedChildren", "aNestedAdHocScope"))
        .as("the inner scope's own children")
        .containsExactly("grandchild");
    assertThat(startableActivitiesOf("adHocMixedChildren", "adHoc"))
        .as("a grandchild is not startable from the outer scope")
        .doesNotContain("grandchild");
  }

  /**
   * A child of an embedded sub process inside the scope is not startable from the ad hoc scope
   * either. Only direct children count, which is what makes the embedded sub process itself the
   * startable unit.
   */
  @org.cibseven.bpm.engine.test.Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/adhoc/AdHocSubProcessStartableActivitiesTest.mixedChildren.bpmn20.xml")
  @Test
  public void grandchildrenOfAnEmbeddedSubProcessAreNotStartable() {
    assertThat(startableActivitiesOf("adHocMixedChildren", "adHoc"))
        .as("innerTask belongs to the embedded sub process, not to the ad hoc scope")
        .doesNotContain("innerTask", "innerStart", "innerEnd");
  }

  /**
   * The second clause of the rule: a flow target is reached from its predecessor, so it cannot also
   * be a starting point.
   *
   * <p>Ignored because no model exercising it can deploy: sequence flows between children are
   * rejected at parse time. The clause costs nothing today and is what makes the rule correct if
   * that rejection is lifted, so the test is written now and waits with its fixture rather than
   * being remembered later.
   */
  @Ignore("CIB7-1882 - sequence flows between ad hoc children are rejected at parse time, so this "
      + "model cannot deploy; the clause it covers becomes reachable when that rejection is lifted. "
      + "CIB7-1852 decided to defer the capability, so this test's fix lives in CIB7-1882")
  @org.cibseven.bpm.engine.test.Deployment
  @Test
  public void innerFlowTarget() {
    assertThat(startableActivitiesOf("adHocInnerFlowTarget", "adHoc"))
        .as("'second' is the target of an inner flow, so it is reached rather than started")
        .containsExactly("first");
  }

}
