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
package org.cibseven.bpm.engine.impl.bpmn.parser;

import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.behavior.ExternalTaskActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.ParseUtil;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.bpm.engine.impl.util.xml.Namespace;

public class DefaultFailedJobParseListener extends AbstractBpmnParseListener {

  protected static final String TYPE = "type";
  protected static final String START_TIMER_EVENT = "startTimerEvent";
  protected static final String BOUNDARY_TIMER = "boundaryTimer";
  protected static final String INTERMEDIATE_SIGNAL_THROW = "intermediateSignalThrow";
  protected static final String INTERMEDIATE_TIMER = "intermediateTimer";
  protected static final String SIGNAL_EVENT_DEFINITION = "signalEventDefinition";
  protected static final String MULTI_INSTANCE_LOOP_CHARACTERISTICS = "multiInstanceLoopCharacteristics";

  protected static final String EXTENSION_ELEMENTS = "extensionElements";
  protected static final String FAILED_JOB_RETRY_TIME_CYCLE = "failedJobRetryTimeCycle";

  /**
   * deprecated since 7.4, use camunda ns.
   */
  @Deprecated
  public static final Namespace FOX_ENGINE_NS = new Namespace("http://www.camunda.com/fox");

  public static final PropertyKey<FailedJobRetryConfiguration> FAILED_JOB_CONFIGURATION = new PropertyKey<FailedJobRetryConfiguration>("FAILED_JOB_CONFIGURATION");

  /**
   * Retry configuration of the external task created on an activity, kept separate from
   * {@link #FAILED_JOB_CONFIGURATION} because the two can come from different sources: the
   * job configuration falls back to the engine-wide {@code failedJobRetryTimeCycle}, the
   * external task configuration to the engine-wide {@code externalTaskFailedJobRetryTimeCycle}.
   * <p>
   * A {@code failedJobRetryTimeCycle} modeled on an activity that is both asyncBefore/asyncAfter
   * and an external task applies to the async job AND to the external task. Before CIB7-562
   * such a cycle only affected the job and the external task had unlimited retries; now the
   * external task's retries are limited by the cycle as well, an incident is raised when they are
   * used up, and the cycle interval replaces the retry timeout reported by the worker.
   */
  public static final PropertyKey<FailedJobRetryConfiguration> EXTERNAL_TASK_FAILED_JOB_CONFIGURATION = new PropertyKey<FailedJobRetryConfiguration>("EXTERNAL_TASK_FAILED_JOB_CONFIGURATION");

  @Override
  public void parseStartEvent(Element startEventElement, ScopeImpl scope, ActivityImpl startEventActivity) {
    String type = startEventActivity.getProperties().get(BpmnProperties.TYPE);
    if (type != null && type.equals(START_TIMER_EVENT) || isAsync(startEventActivity)) {
      this.setFailedJobRetryTimeCycleValue(startEventElement, startEventActivity);
    }
  }

  @Override
  public void parseBoundaryEvent(Element boundaryEventElement, ScopeImpl scopeElement, ActivityImpl nestedActivity) {
    String type = nestedActivity.getProperties().get(BpmnProperties.TYPE);
    if ((type != null && type.equals(BOUNDARY_TIMER)) || isAsync(nestedActivity)) {
      setFailedJobRetryTimeCycleValue(boundaryEventElement, nestedActivity);
    }
  }

  @Override
  public void parseIntermediateThrowEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
    String type = activity.getProperties().get(BpmnProperties.TYPE);
    if (type != null) {
      this.setFailedJobRetryTimeCycleValue(intermediateEventElement, activity);
    }
    if (isExternalTask(activity)) {
      // intermediate message throw event with camunda:type="external"
      setExternalTaskFailedJobRetryTimeCycleValue(intermediateEventElement, activity);
    }
  }

  @Override
  public void parseIntermediateCatchEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
    String type = activity.getProperties().get(BpmnProperties.TYPE);
    if (type != null && type.equals(INTERMEDIATE_TIMER) || isAsync(activity)) {
      this.setFailedJobRetryTimeCycleValue(intermediateEventElement, activity);
    }
  }

  @Override
  public void parseEndEvent(Element endEventElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(endEventElement, activity);
  }

  @Override
  public void parseExclusiveGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(exclusiveGwElement, activity);
  }

  @Override
  public void parseInclusiveGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(exclusiveGwElement, activity);
  }

  @Override
  public void parseEventBasedGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(exclusiveGwElement, activity);
  }

  @Override
  public void parseParallelGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(exclusiveGwElement, activity);
  }

  @Override
  public void parseScriptTask(Element scriptTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(scriptTaskElement, activity);
  }

  @Override
  public void parseServiceTask(Element serviceTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(serviceTaskElement, activity);
  }

  @Override
  public void parseBusinessRuleTask(Element businessRuleTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(businessRuleTaskElement, activity);
  }

  @Override
  public void parseTask(Element taskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(taskElement, activity);
  }

  @Override
  public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(userTaskElement, activity);
  }

  @Override
  public void parseCallActivity(Element callActivityElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(callActivityElement, activity);
  }

  @Override
  public void parseReceiveTask(Element receiveTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(receiveTaskElement, activity);
  }

  @Override
  public void parseSendTask(Element sendTaskElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(sendTaskElement, activity);
  }

  @Override
  public void parseSubProcess(Element subProcessElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(subProcessElement, activity);
  }

  @Override
  public void parseTransaction(Element transactionElement, ScopeImpl scope, ActivityImpl activity) {
    parseActivity(transactionElement, activity);
  }

  protected boolean isAsync(ActivityImpl activity) {
    return activity.isAsyncBefore() || activity.isAsyncAfter();
  }

  protected void parseActivity(Element element, ActivityImpl activity) {

    if (isMultiInstance(activity)) {
      // in case of multi-instance, the extension elements is set according to the async attributes
      // the extension for multi-instance body is set on the element of the activity
      ActivityImpl miBody = activity.getParentFlowScopeActivity();
      if (isAsync(miBody)) {
        setFailedJobRetryTimeCycleValue(element, miBody);
      }
      // the extension for inner activity is set on the multiInstanceLoopCharacteristics element
      if (isAsync(activity)) {
        Element multiInstanceLoopCharacteristics = element.element(MULTI_INSTANCE_LOOP_CHARACTERISTICS);
        setFailedJobRetryTimeCycleValue(multiInstanceLoopCharacteristics, activity);
      }

    } else if (isAsync(activity)) {
      setFailedJobRetryTimeCycleValue(element, activity);
    }

    if (isExternalTask(activity)) {
      // external tasks have no separate "MI body" variant the way jobs do; the single,
      // natural place to model a retry cycle is the service task's own extensionElements,
      // the same whether or not the activity is multi-instance - never nested inside
      // multiInstanceLoopCharacteristics, which exists only so jobs can tell the MI body's
      // cycle apart from the inner activity's
      setExternalTaskFailedJobRetryTimeCycleValue(element, activity);
    }
  }

  protected void setFailedJobRetryTimeCycleValue(Element element, ActivityImpl activity) {
    String failedJobRetryTimeCycleConfiguration = readFailedJobRetryTimeCycleElement(element);

    if (failedJobRetryTimeCycleConfiguration == null || failedJobRetryTimeCycleConfiguration.isEmpty()) {
      failedJobRetryTimeCycleConfiguration = Context.getProcessEngineConfiguration().getFailedJobRetryTimeCycle();
    }

    if (failedJobRetryTimeCycleConfiguration != null) {
      FailedJobRetryConfiguration configuration = ParseUtil.parseRetryIntervals(failedJobRetryTimeCycleConfiguration);
      activity.getProperties().set(FAILED_JOB_CONFIGURATION, configuration);
    }
  }

  /**
   * Resolves the retry cycle for the external task created on this activity, independently
   * of {@link #setFailedJobRetryTimeCycleValue}. A modeled cycle always wins for the external
   * task, same as it does for a job: the engine-wide external task default only applies when
   * this activity has no modeled cycle of its own. Earlier this also tried to detect and
   * suppress a cycle "meant for" an asyncBefore/asyncAfter job on the same activity, but the
   * CIBseven modeler only exposes the retry-cycle field at all once asyncBefore/After is
   * enabled - so that check fired for essentially every modeled external task cycle and broke
   * the feature for anyone using the standard tooling. Removed; see CIB-562 follow-up.
   */
  protected void setExternalTaskFailedJobRetryTimeCycleValue(Element element, ActivityImpl activity) {
    String externalTaskRetryTimeCycleConfiguration = readFailedJobRetryTimeCycleElement(element);

    if (externalTaskRetryTimeCycleConfiguration == null || externalTaskRetryTimeCycleConfiguration.isEmpty()) {
      externalTaskRetryTimeCycleConfiguration = Context.getProcessEngineConfiguration().getExternalTaskFailedJobRetryTimeCycle();
    }

    if (externalTaskRetryTimeCycleConfiguration != null) {
      FailedJobRetryConfiguration configuration = ParseUtil.parseRetryIntervals(externalTaskRetryTimeCycleConfiguration);
      if (!ParseUtil.hasValidRetryIntervals(configuration)) {
        // e.g. "PT1M,PT5X": parseRetryIntervals only validates single-interval cycles
        ProcessEngineLogger.EXTERNAL_TASK_LOGGER.invalidRetryTimeCycle(activity.getId(), externalTaskRetryTimeCycleConfiguration);
        return;
      }
      activity.getProperties().set(EXTERNAL_TASK_FAILED_JOB_CONFIGURATION, configuration);
    }
  }

  protected String readFailedJobRetryTimeCycleElement(Element element) {
    String failedJobRetryTimeCycleConfiguration = null;
    Element extensionElements = element.element(EXTENSION_ELEMENTS);
    if (extensionElements != null) {
      Element failedJobRetryTimeCycleElement = extensionElements.elementNS(FOX_ENGINE_NS, FAILED_JOB_RETRY_TIME_CYCLE);
      if (failedJobRetryTimeCycleElement == null) {
        // try to get it from the activiti namespace
        failedJobRetryTimeCycleElement = extensionElements.elementNS(BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS, FAILED_JOB_RETRY_TIME_CYCLE);
      }

      if (failedJobRetryTimeCycleElement != null) {
        failedJobRetryTimeCycleConfiguration = failedJobRetryTimeCycleElement.getText();
      }
    }
    return failedJobRetryTimeCycleConfiguration;
  }

  protected boolean isExternalTask(ActivityImpl activity) {
    return activity.getActivityBehavior() instanceof ExternalTaskActivityBehavior;
  }

  protected boolean isMultiInstance(ActivityImpl activity) {
    // #isMultiInstance() don't work since the property is not set yet
    ActivityImpl parent = activity.getParentFlowScopeActivity();
    return parent != null && parent.getActivityBehavior() instanceof MultiInstanceActivityBehavior;
  }

}
