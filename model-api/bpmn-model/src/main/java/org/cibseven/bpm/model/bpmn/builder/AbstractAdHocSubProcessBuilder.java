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
package org.cibseven.bpm.model.bpmn.builder;

import org.cibseven.bpm.model.bpmn.AdHocOrdering;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.instance.Activity;
import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.CompletionCondition;
import org.cibseven.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.cibseven.bpm.model.bpmn.instance.dc.Bounds;

/**
 * Builder for a BPMN adHocSubProcess.
 *
 * <p>Children are added with {@link #child(Class, String)} rather than through the flow-based
 * chain the other sub-process builders use. That is not an omission: BPMN 2.0.0 section 10.3.5
 * says the activities in an ad hoc scope have "no REQUIRED sequence relationships" and are
 * "generally disconnected from each other", and forbids a start event inside the scope, so there
 * is no flow to chain from. {@code child} returns the created element instead of a builder, so a
 * caller cannot accidentally continue the enclosing chain from inside the scope.
 */
public abstract class AbstractAdHocSubProcessBuilder<B extends AbstractAdHocSubProcessBuilder<B>>
    extends AbstractActivityBuilder<B, AdHocSubProcess> {

  protected AbstractAdHocSubProcessBuilder(BpmnModelInstance modelInstance, AdHocSubProcess element, Class<?> selfType) {
    super(modelInstance, element, selfType);
  }

  /**
   * Adds an activity to the scope, with no sequence flow to or from it.
   *
   * @param activityType the type of activity to create, for example {@code UserTask.class}
   * @param id the id of the new activity, or null to generate one
   * @return the created activity, so that it can be configured through its own builder
   */
  public <T extends Activity> T child(Class<T> activityType, String id) {
    // Counted before the child is added, so it is the index of the one about to be created.
    int index = element.getChildElementsByType(Activity.class).size();
    T child = createChild(element, activityType, id);
    layOutInsideScope(createBpmnShape(child), index);
    return child;
  }

  /**
   * Places a freshly created child shape inside the scope's own shape, in a row.
   *
   * <p>{@link #createBpmnShape} sets a width and a height but leaves the position at the origin, so
   * a caller that does not place the shape leaves every child stacked at (0,0) and outside the
   * scope it belongs to. The other builders each position the shapes they create; this is the ad hoc
   * equivalent, laying children out left to right because they have no sequence flow to follow.
   */
  protected void layOutInsideScope(BpmnShape childShape, int index) {
    BpmnShape scopeShape = findBpmnShape(element);
    if (scopeShape == null || childShape == null) {
      return;
    }
    Bounds scope = scopeShape.getBounds();
    Bounds child = childShape.getBounds();

    child.setX(scope.getX() + SPACE + index * (child.getWidth() + SPACE));
    child.setY(scope.getY() + scope.getHeight() / 2 - child.getHeight() / 2);

    // Grow the scope so it still contains the row; the default 350 wide holds two children.
    double needed = child.getX() + child.getWidth() + SPACE - scope.getX();
    if (needed > scope.getWidth()) {
      scope.setWidth(needed);
    }
  }

  /**
   * Sets the ordering of the scope.
   *
   * <p>{@link AdHocOrdering#Parallel} is the default, so setting it writes an attribute that means
   * what its absence already meant.
   *
   * @return the builder object
   */
  public B ordering(AdHocOrdering ordering) {
    element.setOrdering(ordering);
    return myself;
  }

  /**
   * Sets whether satisfying the completion condition cancels the activities still running.
   *
   * <p>True is the default. BPMN 2.0.0 Table 10.22 says this attribute "is used only if ordering is
   * parallel".
   *
   * @return the builder object
   */
  public B cancelRemainingInstances(boolean cancelRemainingInstances) {
    element.setCancelRemainingInstances(cancelRemainingInstances);
    return myself;
  }

  /**
   * Sets the completion condition of the scope. When it evaluates to true the scope ends.
   *
   * @param conditionExpression the expression to evaluate, for example {@code ${approved}}
   * @return the builder object
   */
  public B completionCondition(String conditionExpression) {
    // getCreateSingleChild, not createChild: the schema allows at most one completionCondition, so
    // appending would turn a second call into an invalid model rather than an updated expression.
    // The multi-instance builder does the same for the same reason.
    CompletionCondition condition = getCreateSingleChild(element, CompletionCondition.class);
    condition.setTextContent(conditionExpression);
    return myself;
  }

}
