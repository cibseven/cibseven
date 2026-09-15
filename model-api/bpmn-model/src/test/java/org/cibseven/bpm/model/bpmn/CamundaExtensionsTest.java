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
package org.cibseven.bpm.model.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.BUSINESS_RULE_TASK;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.CALL_ACTIVITY_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.END_EVENT_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.PROCESS_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.SCRIPT_TASK_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.SEND_TASK_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.SEQUENCE_FLOW_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.SERVICE_TASK_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.START_EVENT_ID;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_CLASS_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_CLASS_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_DELEGATE_EXPRESSION_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_DELEGATE_EXPRESSION_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_DUE_DATE_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_DUE_DATE_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_EXECUTION_EVENT_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_EXECUTION_EVENT_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_EXPRESSION_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_EXPRESSION_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_FLOW_NODE_JOB_PRIORITY;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_GROUPS_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_GROUPS_LIST_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_GROUPS_LIST_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_GROUPS_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_HISTORY_TIME_TO_LIVE;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_PRIORITY_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_PRIORITY_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_PROCESS_JOB_PRIORITY;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_PROCESS_TASK_PRIORITY;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_SERVICE_TASK_PRIORITY;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_STRING_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_STRING_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_TASK_EVENT_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_TASK_EVENT_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_TYPE_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_TYPE_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_USERS_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_USERS_LIST_API;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_USERS_LIST_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.TEST_USERS_XML;
import static org.cibseven.bpm.model.bpmn.BpmnTestConstants.USER_TASK_ID;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.ACTIVITI_NS;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ATTRIBUTE_ERROR_CODE_VARIABLE;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_ATTRIBUTE_ERROR_MESSAGE_VARIABLE;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.CAMUNDA_NS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.cibseven.bpm.model.bpmn.instance.BaseElement;
import org.cibseven.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.cibseven.bpm.model.bpmn.instance.BusinessRuleTask;
import org.cibseven.bpm.model.bpmn.instance.CallActivity;
import org.cibseven.bpm.model.bpmn.instance.EndEvent;
import org.cibseven.bpm.model.bpmn.instance.Error;
import org.cibseven.bpm.model.bpmn.instance.ErrorEventDefinition;
import org.cibseven.bpm.model.bpmn.instance.Expression;
import org.cibseven.bpm.model.bpmn.instance.MessageEventDefinition;
import org.cibseven.bpm.model.bpmn.instance.ParallelGateway;
import org.cibseven.bpm.model.bpmn.instance.Process;
import org.cibseven.bpm.model.bpmn.instance.ScriptTask;
import org.cibseven.bpm.model.bpmn.instance.SendTask;
import org.cibseven.bpm.model.bpmn.instance.SequenceFlow;
import org.cibseven.bpm.model.bpmn.instance.ServiceTask;
import org.cibseven.bpm.model.bpmn.instance.StartEvent;
import org.cibseven.bpm.model.bpmn.instance.TimerEventDefinition;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaConnector;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaConnectorId;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaConstraint;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaEntry;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaExecutionListener;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFailedJobRetryTimeCycle;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaField;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFormData;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFormField;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFormProperty;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaIn;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaInputOutput;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaInputParameter;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaList;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaMap;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaOut;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaOutputParameter;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaPotentialStarter;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaProperties;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaProperty;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaScript;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaTaskListener;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Sebastian Menski
 * @author Ronny Bräunlich
 */
public class CamundaExtensionsTest {

  private String namespace;
  private BpmnModelInstance originalModelInstance;
  private Process process;
  private StartEvent startEvent;
  private SequenceFlow sequenceFlow;
  private UserTask userTask;
  private ServiceTask serviceTask;
  private SendTask sendTask;
  private ScriptTask scriptTask;
  private CallActivity callActivity;
  private BusinessRuleTask businessRuleTask;
  private EndEvent endEvent;
  private MessageEventDefinition messageEventDefinition;
  private ParallelGateway parallelGateway;
  private BpmnModelInstance modelInstance;
  private Error error;

  public static Stream<Arguments> parameters() {
    return Stream.of(new Object[][]{
        {CAMUNDA_NS, Bpmn.readModelFromStream(CamundaExtensionsTest.class.getResourceAsStream("CamundaExtensionsTest.xml"))},
        //for compatability reasons we gotta check the old namespace, too
        {ACTIVITI_NS, Bpmn.readModelFromStream(CamundaExtensionsTest.class.getResourceAsStream("CamundaExtensionsCompatabilityTest.xml"))}
    }).map(Arguments::of);
  }
  
  public void setUp(BpmnModelInstance originalModelInstance) {
    if (originalModelInstance != null) {
      modelInstance = originalModelInstance.clone();
      process = modelInstance.getModelElementById(PROCESS_ID);
      startEvent = modelInstance.getModelElementById(START_EVENT_ID);
      sequenceFlow = modelInstance.getModelElementById(SEQUENCE_FLOW_ID);
      userTask = modelInstance.getModelElementById(USER_TASK_ID);
      serviceTask = modelInstance.getModelElementById(SERVICE_TASK_ID);
      sendTask = modelInstance.getModelElementById(SEND_TASK_ID);
      scriptTask = modelInstance.getModelElementById(SCRIPT_TASK_ID);
      callActivity = modelInstance.getModelElementById(CALL_ACTIVITY_ID);
      businessRuleTask = modelInstance.getModelElementById(BUSINESS_RULE_TASK);
      endEvent = modelInstance.getModelElementById(END_EVENT_ID);
      messageEventDefinition = (MessageEventDefinition) endEvent.getEventDefinitions().iterator().next();
      parallelGateway = modelInstance.getModelElementById("parallelGateway");
      error = modelInstance.getModelElementById("error");
    }
  }

  @AfterEach
  public void validateModel() {
    if (modelInstance != null) {
      Bpmn.validateModel(modelInstance);
    }
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testAssignee(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaAssignee()).isEqualTo(TEST_STRING_XML);
    userTask.setCamundaAssignee(TEST_STRING_API);
    assertThat(userTask.getCamundaAssignee()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testAsync(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.isCamundaAsync()).isFalse();
    assertThat(userTask.isCamundaAsync()).isTrue();
    assertThat(parallelGateway.isCamundaAsync()).isTrue();

    startEvent.setCamundaAsync(true);
    userTask.setCamundaAsync(false);
    parallelGateway.setCamundaAsync(false);

    assertThat(startEvent.isCamundaAsync()).isTrue();
    assertThat(userTask.isCamundaAsync()).isFalse();
    assertThat(parallelGateway.isCamundaAsync()).isFalse();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testAsyncBefore(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.isCamundaAsyncBefore()).isTrue();
    assertThat(endEvent.isCamundaAsyncBefore()).isTrue();
    assertThat(userTask.isCamundaAsyncBefore()).isTrue();
    assertThat(parallelGateway.isCamundaAsyncBefore()).isTrue();

    startEvent.setCamundaAsyncBefore(false);
    endEvent.setCamundaAsyncBefore(false);
    userTask.setCamundaAsyncBefore(false);
    parallelGateway.setCamundaAsyncBefore(false);

    assertThat(startEvent.isCamundaAsyncBefore()).isFalse();
    assertThat(endEvent.isCamundaAsyncBefore()).isFalse();
    assertThat(userTask.isCamundaAsyncBefore()).isFalse();
    assertThat(parallelGateway.isCamundaAsyncBefore()).isFalse();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testAsyncAfter(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.isCamundaAsyncAfter()).isTrue();
    assertThat(endEvent.isCamundaAsyncAfter()).isTrue();
    assertThat(userTask.isCamundaAsyncAfter()).isTrue();
    assertThat(parallelGateway.isCamundaAsyncAfter()).isTrue();

    startEvent.setCamundaAsyncAfter(false);
    endEvent.setCamundaAsyncAfter(false);
    userTask.setCamundaAsyncAfter(false);
    parallelGateway.setCamundaAsyncAfter(false);

    assertThat(startEvent.isCamundaAsyncAfter()).isFalse();
    assertThat(endEvent.isCamundaAsyncAfter()).isFalse();
    assertThat(userTask.isCamundaAsyncAfter()).isFalse();
    assertThat(parallelGateway.isCamundaAsyncAfter()).isFalse();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFlowNodeJobPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.getCamundaJobPriority()).isEqualTo(TEST_FLOW_NODE_JOB_PRIORITY);
    assertThat(endEvent.getCamundaJobPriority()).isEqualTo(TEST_FLOW_NODE_JOB_PRIORITY);
    assertThat(userTask.getCamundaJobPriority()).isEqualTo(TEST_FLOW_NODE_JOB_PRIORITY);
    assertThat(parallelGateway.getCamundaJobPriority()).isEqualTo(TEST_FLOW_NODE_JOB_PRIORITY);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testProcessJobPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaJobPriority()).isEqualTo(TEST_PROCESS_JOB_PRIORITY);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testProcessTaskPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaTaskPriority()).isEqualTo(TEST_PROCESS_TASK_PRIORITY);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testHistoryTimeToLive(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaHistoryTimeToLive()).isEqualTo(TEST_HISTORY_TIME_TO_LIVE);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testIsStartableInTasklist(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.isCamundaStartableInTasklist()).isEqualTo(false);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testVersionTag(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaVersionTag()).isEqualTo("v1.0.0");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testServiceTaskPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaTaskPriority()).isEqualTo(TEST_SERVICE_TASK_PRIORITY);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCalledElementBinding(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCalledElementBinding()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCalledElementBinding(TEST_STRING_API);
    assertThat(callActivity.getCamundaCalledElementBinding()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCalledElementVersion(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCalledElementVersion()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCalledElementVersion(TEST_STRING_API);
    assertThat(callActivity.getCamundaCalledElementVersion()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCalledElementVersionTag(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCalledElementVersionTag()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCalledElementVersionTag(TEST_STRING_API);
    assertThat(callActivity.getCamundaCalledElementVersionTag()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCalledElementTenantId(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCalledElementTenantId()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCalledElementTenantId(TEST_STRING_API);
    assertThat(callActivity.getCamundaCalledElementTenantId()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCaseRef(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCaseRef()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCaseRef(TEST_STRING_API);
    assertThat(callActivity.getCamundaCaseRef()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCaseBinding(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCaseBinding()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCaseBinding(TEST_STRING_API);
    assertThat(callActivity.getCamundaCaseBinding()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCaseVersion(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCaseVersion()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCaseVersion(TEST_STRING_API);
    assertThat(callActivity.getCamundaCaseVersion()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCaseTenantId(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaCaseTenantId()).isEqualTo(TEST_STRING_XML);
    callActivity.setCamundaCaseTenantId(TEST_STRING_API);
    assertThat(callActivity.getCamundaCaseTenantId()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDecisionRef(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaDecisionRef()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaDecisionRef(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaDecisionRef()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDecisionRefBinding(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaDecisionRefBinding()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaDecisionRefBinding(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaDecisionRefBinding()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDecisionRefVersion(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaDecisionRefVersion()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaDecisionRefVersion(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaDecisionRefVersion()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDecisionRefVersionTag(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaDecisionRefVersionTag()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaDecisionRefVersionTag(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaDecisionRefVersionTag()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDecisionRefTenantId(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaDecisionRefTenantId()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaDecisionRefTenantId(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaDecisionRefTenantId()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testMapDecisionResult(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaMapDecisionResult()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaMapDecisionResult(TEST_STRING_API);
    assertThat(businessRuleTask.getCamundaMapDecisionResult()).isEqualTo(TEST_STRING_API);
  }


  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testTaskPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(businessRuleTask.getCamundaTaskPriority()).isEqualTo(TEST_STRING_XML);
    businessRuleTask.setCamundaTaskPriority(TEST_SERVICE_TASK_PRIORITY);
    assertThat(businessRuleTask.getCamundaTaskPriority()).isEqualTo(TEST_SERVICE_TASK_PRIORITY);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCandidateGroups(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateGroups()).isEqualTo(TEST_GROUPS_XML);
    assertThat(userTask.getCamundaCandidateGroupsList()).containsAll(TEST_GROUPS_LIST_XML);
    userTask.setCamundaCandidateGroups(TEST_GROUPS_API);
    assertThat(userTask.getCamundaCandidateGroups()).isEqualTo(TEST_GROUPS_API);
    assertThat(userTask.getCamundaCandidateGroupsList()).containsAll(TEST_GROUPS_LIST_API);
    userTask.setCamundaCandidateGroupsList(TEST_GROUPS_LIST_XML);
    assertThat(userTask.getCamundaCandidateGroups()).isEqualTo(TEST_GROUPS_XML);
    assertThat(userTask.getCamundaCandidateGroupsList()).containsAll(TEST_GROUPS_LIST_XML);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCandidateStarterGroups(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaCandidateStarterGroups()).isEqualTo(TEST_GROUPS_XML);
    assertThat(process.getCamundaCandidateStarterGroupsList()).containsAll(TEST_GROUPS_LIST_XML);
    process.setCamundaCandidateStarterGroups(TEST_GROUPS_API);
    assertThat(process.getCamundaCandidateStarterGroups()).isEqualTo(TEST_GROUPS_API);
    assertThat(process.getCamundaCandidateStarterGroupsList()).containsAll(TEST_GROUPS_LIST_API);
    process.setCamundaCandidateStarterGroupsList(TEST_GROUPS_LIST_XML);
    assertThat(process.getCamundaCandidateStarterGroups()).isEqualTo(TEST_GROUPS_XML);
    assertThat(process.getCamundaCandidateStarterGroupsList()).containsAll(TEST_GROUPS_LIST_XML);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCandidateStarterUsers(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(process.getCamundaCandidateStarterUsers()).isEqualTo(TEST_USERS_XML);
    assertThat(process.getCamundaCandidateStarterUsersList()).containsAll(TEST_USERS_LIST_XML);
    process.setCamundaCandidateStarterUsers(TEST_USERS_API);
    assertThat(process.getCamundaCandidateStarterUsers()).isEqualTo(TEST_USERS_API);
    assertThat(process.getCamundaCandidateStarterUsersList()).containsAll(TEST_USERS_LIST_API);
    process.setCamundaCandidateStarterUsersList(TEST_USERS_LIST_XML);
    assertThat(process.getCamundaCandidateStarterUsers()).isEqualTo(TEST_USERS_XML);
    assertThat(process.getCamundaCandidateStarterUsersList()).containsAll(TEST_USERS_LIST_XML);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCandidateUsers(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateUsers()).isEqualTo(TEST_USERS_XML);
    assertThat(userTask.getCamundaCandidateUsersList()).containsAll(TEST_USERS_LIST_XML);
    userTask.setCamundaCandidateUsers(TEST_USERS_API);
    assertThat(userTask.getCamundaCandidateUsers()).isEqualTo(TEST_USERS_API);
    assertThat(userTask.getCamundaCandidateUsersList()).containsAll(TEST_USERS_LIST_API);
    userTask.setCamundaCandidateUsersList(TEST_USERS_LIST_XML);
    assertThat(userTask.getCamundaCandidateUsers()).isEqualTo(TEST_USERS_XML);
    assertThat(userTask.getCamundaCandidateUsersList()).containsAll(TEST_USERS_LIST_XML);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testClass(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaClass()).isEqualTo(TEST_CLASS_XML);
    assertThat(messageEventDefinition.getCamundaClass()).isEqualTo(TEST_CLASS_XML);

    serviceTask.setCamundaClass(TEST_CLASS_API);
    messageEventDefinition.setCamundaClass(TEST_CLASS_API);

    assertThat(serviceTask.getCamundaClass()).isEqualTo(TEST_CLASS_API);
    assertThat(messageEventDefinition.getCamundaClass()).isEqualTo(TEST_CLASS_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDelegateExpression(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_XML);
    assertThat(messageEventDefinition.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_XML);

    serviceTask.setCamundaDelegateExpression(TEST_DELEGATE_EXPRESSION_API);
    messageEventDefinition.setCamundaDelegateExpression(TEST_DELEGATE_EXPRESSION_API);

    assertThat(serviceTask.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_API);
    assertThat(messageEventDefinition.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testDueDate(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaDueDate()).isEqualTo(TEST_DUE_DATE_XML);
    userTask.setCamundaDueDate(TEST_DUE_DATE_API);
    assertThat(userTask.getCamundaDueDate()).isEqualTo(TEST_DUE_DATE_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testErrorCodeVariable(String namespace, BpmnModelInstance model) {
    setUp(model);
    ErrorEventDefinition errorEventDefinition = startEvent.getChildElementsByType(ErrorEventDefinition.class).iterator().next();
    assertThat(errorEventDefinition.getAttributeValueNs(namespace, CAMUNDA_ATTRIBUTE_ERROR_CODE_VARIABLE)).isEqualTo("errorVariable");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testErrorMessageVariable(String namespace, BpmnModelInstance model) {
    setUp(model);
    ErrorEventDefinition errorEventDefinition = startEvent.getChildElementsByType(ErrorEventDefinition.class).iterator().next();
    assertThat(errorEventDefinition.getAttributeValueNs(namespace, CAMUNDA_ATTRIBUTE_ERROR_MESSAGE_VARIABLE)).isEqualTo("errorMessageVariable");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testErrorMessage(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(error.getCamundaErrorMessage()).isEqualTo(TEST_STRING_XML);
    error.setCamundaErrorMessage(TEST_STRING_API);
    assertThat(error.getCamundaErrorMessage()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testExclusive(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.isCamundaExclusive()).isTrue();
    assertThat(userTask.isCamundaExclusive()).isFalse();
    userTask.setCamundaExclusive(true);
    assertThat(userTask.isCamundaExclusive()).isTrue();
    assertThat(parallelGateway.isCamundaExclusive()).isTrue();
    parallelGateway.setCamundaExclusive(false);
    assertThat(parallelGateway.isCamundaExclusive()).isFalse();

    assertThat(callActivity.isCamundaExclusive()).isFalse();
    callActivity.setCamundaExclusive(true);
    assertThat(callActivity.isCamundaExclusive()).isTrue();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testExpression(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(messageEventDefinition.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    serviceTask.setCamundaExpression(TEST_EXPRESSION_API);
    messageEventDefinition.setCamundaExpression(TEST_EXPRESSION_API);
    assertThat(serviceTask.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(messageEventDefinition.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFormHandlerClass(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.getCamundaFormHandlerClass()).isEqualTo(TEST_CLASS_XML);
    assertThat(userTask.getCamundaFormHandlerClass()).isEqualTo(TEST_CLASS_XML);
    startEvent.setCamundaFormHandlerClass(TEST_CLASS_API);
    userTask.setCamundaFormHandlerClass(TEST_CLASS_API);
    assertThat(startEvent.getCamundaFormHandlerClass()).isEqualTo(TEST_CLASS_API);
    assertThat(userTask.getCamundaFormHandlerClass()).isEqualTo(TEST_CLASS_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFormKey(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.getCamundaFormKey()).isEqualTo(TEST_STRING_XML);
    assertThat(userTask.getCamundaFormKey()).isEqualTo(TEST_STRING_XML);
    startEvent.setCamundaFormKey(TEST_STRING_API);
    userTask.setCamundaFormKey(TEST_STRING_API);
    assertThat(startEvent.getCamundaFormKey()).isEqualTo(TEST_STRING_API);
    assertThat(userTask.getCamundaFormKey()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testInitiator(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(startEvent.getCamundaInitiator()).isEqualTo(TEST_STRING_XML);
    startEvent.setCamundaInitiator(TEST_STRING_API);
    assertThat(startEvent.getCamundaInitiator()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testPriority(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaPriority()).isEqualTo(TEST_PRIORITY_XML);
    userTask.setCamundaPriority(TEST_PRIORITY_API);
    assertThat(userTask.getCamundaPriority()).isEqualTo(TEST_PRIORITY_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testResultVariable(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaResultVariable()).isEqualTo(TEST_STRING_XML);
    assertThat(messageEventDefinition.getCamundaResultVariable()).isEqualTo(TEST_STRING_XML);
    serviceTask.setCamundaResultVariable(TEST_STRING_API);
    messageEventDefinition.setCamundaResultVariable(TEST_STRING_API);
    assertThat(serviceTask.getCamundaResultVariable()).isEqualTo(TEST_STRING_API);
    assertThat(messageEventDefinition.getCamundaResultVariable()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testType(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaType()).isEqualTo(TEST_TYPE_XML);
    assertThat(messageEventDefinition.getCamundaType()).isEqualTo(TEST_STRING_XML);
    serviceTask.setCamundaType(TEST_TYPE_API);
    messageEventDefinition.setCamundaType(TEST_STRING_API);
    assertThat(serviceTask.getCamundaType()).isEqualTo(TEST_TYPE_API);
    assertThat(messageEventDefinition.getCamundaType()).isEqualTo(TEST_STRING_API);

  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testTopic(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(serviceTask.getCamundaTopic()).isEqualTo(TEST_STRING_XML);
    assertThat(messageEventDefinition.getCamundaTopic()).isEqualTo(TEST_STRING_XML);
    serviceTask.setCamundaTopic(TEST_TYPE_API);
    messageEventDefinition.setCamundaTopic(TEST_STRING_API);
    assertThat(serviceTask.getCamundaTopic()).isEqualTo(TEST_TYPE_API);
    assertThat(messageEventDefinition.getCamundaTopic()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testVariableMappingClass(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaVariableMappingClass()).isEqualTo(TEST_CLASS_XML);
    callActivity.setCamundaVariableMappingClass(TEST_CLASS_API);
    assertThat(callActivity.getCamundaVariableMappingClass()).isEqualTo(TEST_CLASS_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testVariableMappingDelegateExpression(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(callActivity.getCamundaVariableMappingDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_XML);
    callActivity.setCamundaVariableMappingDelegateExpression(TEST_DELEGATE_EXPRESSION_API);
    assertThat(callActivity.getCamundaVariableMappingDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testExecutionListenerExtension(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaExecutionListener processListener = process.getExtensionElements().getElementsQuery().filterByType(CamundaExecutionListener.class).singleResult();
    CamundaExecutionListener startEventListener = startEvent.getExtensionElements().getElementsQuery().filterByType(CamundaExecutionListener.class).singleResult();
    CamundaExecutionListener serviceTaskListener = serviceTask.getExtensionElements().getElementsQuery().filterByType(CamundaExecutionListener.class).singleResult();
    assertThat(processListener.getCamundaClass()).isEqualTo(TEST_CLASS_XML);
    assertThat(processListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_XML);
    assertThat(startEventListener.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(startEventListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_XML);
    assertThat(serviceTaskListener.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_XML);
    assertThat(serviceTaskListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_XML);
    processListener.setCamundaClass(TEST_CLASS_API);
    processListener.setCamundaEvent(TEST_EXECUTION_EVENT_API);
    startEventListener.setCamundaExpression(TEST_EXPRESSION_API);
    startEventListener.setCamundaEvent(TEST_EXECUTION_EVENT_API);
    serviceTaskListener.setCamundaDelegateExpression(TEST_DELEGATE_EXPRESSION_API);
    serviceTaskListener.setCamundaEvent(TEST_EXECUTION_EVENT_API);
    assertThat(processListener.getCamundaClass()).isEqualTo(TEST_CLASS_API);
    assertThat(processListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_API);
    assertThat(startEventListener.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(startEventListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_API);
    assertThat(serviceTaskListener.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_API);
    assertThat(serviceTaskListener.getCamundaEvent()).isEqualTo(TEST_EXECUTION_EVENT_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaScriptExecutionListener(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaExecutionListener sequenceFlowListener = sequenceFlow.getExtensionElements().getElementsQuery().filterByType(CamundaExecutionListener.class).singleResult();

    CamundaScript script = sequenceFlowListener.getCamundaScript();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("groovy");
    assertThat(script.getCamundaResource()).isNull();
    assertThat(script.getTextContent()).isEqualTo("println 'Hello World'");

    CamundaScript newScript = modelInstance.newInstance(CamundaScript.class);
    newScript.setCamundaScriptFormat("groovy");
    newScript.setCamundaResource("test.groovy");
    sequenceFlowListener.setCamundaScript(newScript);

    script = sequenceFlowListener.getCamundaScript();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("groovy");
    assertThat(script.getCamundaResource()).isEqualTo("test.groovy");
    assertThat(script.getTextContent()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFailedJobRetryTimeCycleExtension(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaFailedJobRetryTimeCycle timeCycle = sendTask.getExtensionElements().getElementsQuery().filterByType(CamundaFailedJobRetryTimeCycle.class).singleResult();
    assertThat(timeCycle.getTextContent()).isEqualTo(TEST_STRING_XML);
    timeCycle.setTextContent(TEST_STRING_API);
    assertThat(timeCycle.getTextContent()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFieldExtension(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaField field = sendTask.getExtensionElements().getElementsQuery().filterByType(CamundaField.class).singleResult();
    assertThat(field.getCamundaName()).isEqualTo(TEST_STRING_XML);
    assertThat(field.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(field.getCamundaStringValue()).isEqualTo(TEST_STRING_XML);
    assertThat(field.getCamundaExpressionChild().getTextContent()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(field.getCamundaString().getTextContent()).isEqualTo(TEST_STRING_XML);
    field.setCamundaName(TEST_STRING_API);
    field.setCamundaExpression(TEST_EXPRESSION_API);
    field.setCamundaStringValue(TEST_STRING_API);
    field.getCamundaExpressionChild().setTextContent(TEST_EXPRESSION_API);
    field.getCamundaString().setTextContent(TEST_STRING_API);
    assertThat(field.getCamundaName()).isEqualTo(TEST_STRING_API);
    assertThat(field.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(field.getCamundaStringValue()).isEqualTo(TEST_STRING_API);
    assertThat(field.getCamundaExpressionChild().getTextContent()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(field.getCamundaString().getTextContent()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFormData(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaFormData formData = userTask.getExtensionElements().getElementsQuery().filterByType(CamundaFormData.class).singleResult();
    CamundaFormField formField = formData.getCamundaFormFields().iterator().next();
    assertThat(formField.getCamundaId()).isEqualTo(TEST_STRING_XML);
    assertThat(formField.getCamundaLabel()).isEqualTo(TEST_STRING_XML);
    assertThat(formField.getCamundaType()).isEqualTo(TEST_STRING_XML);
    assertThat(formField.getCamundaDatePattern()).isEqualTo(TEST_STRING_XML);
    assertThat(formField.getCamundaDefaultValue()).isEqualTo(TEST_STRING_XML);
    formField.setCamundaId(TEST_STRING_API);
    formField.setCamundaLabel(TEST_STRING_API);
    formField.setCamundaType(TEST_STRING_API);
    formField.setCamundaDatePattern(TEST_STRING_API);
    formField.setCamundaDefaultValue(TEST_STRING_API);
    assertThat(formField.getCamundaId()).isEqualTo(TEST_STRING_API);
    assertThat(formField.getCamundaLabel()).isEqualTo(TEST_STRING_API);
    assertThat(formField.getCamundaType()).isEqualTo(TEST_STRING_API);
    assertThat(formField.getCamundaDatePattern()).isEqualTo(TEST_STRING_API);
    assertThat(formField.getCamundaDefaultValue()).isEqualTo(TEST_STRING_API);

    CamundaProperty property = formField.getCamundaProperties().getCamundaProperties().iterator().next();
    assertThat(property.getCamundaId()).isEqualTo(TEST_STRING_XML);
    assertThat(property.getCamundaValue()).isEqualTo(TEST_STRING_XML);
    property.setCamundaId(TEST_STRING_API);
    property.setCamundaValue(TEST_STRING_API);
    assertThat(property.getCamundaId()).isEqualTo(TEST_STRING_API);
    assertThat(property.getCamundaValue()).isEqualTo(TEST_STRING_API);

    CamundaConstraint constraint = formField.getCamundaValidation().getCamundaConstraints().iterator().next();
    assertThat(constraint.getCamundaName()).isEqualTo(TEST_STRING_XML);
    assertThat(constraint.getCamundaConfig()).isEqualTo(TEST_STRING_XML);
    constraint.setCamundaName(TEST_STRING_API);
    constraint.setCamundaConfig(TEST_STRING_API);
    assertThat(constraint.getCamundaName()).isEqualTo(TEST_STRING_API);
    assertThat(constraint.getCamundaConfig()).isEqualTo(TEST_STRING_API);

    CamundaValue value = formField.getCamundaValues().iterator().next();
    assertThat(value.getCamundaId()).isEqualTo(TEST_STRING_XML);
    assertThat(value.getCamundaName()).isEqualTo(TEST_STRING_XML);
    value.setCamundaId(TEST_STRING_API);
    value.setCamundaName(TEST_STRING_API);
    assertThat(value.getCamundaId()).isEqualTo(TEST_STRING_API);
    assertThat(value.getCamundaName()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testFormProperty(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaFormProperty formProperty = startEvent.getExtensionElements().getElementsQuery().filterByType(CamundaFormProperty.class).singleResult();
    assertThat(formProperty.getCamundaId()).isEqualTo(TEST_STRING_XML);
    assertThat(formProperty.getCamundaName()).isEqualTo(TEST_STRING_XML);
    assertThat(formProperty.getCamundaType()).isEqualTo(TEST_STRING_XML);
    assertThat(formProperty.isCamundaRequired()).isFalse();
    assertThat(formProperty.isCamundaReadable()).isTrue();
    assertThat(formProperty.isCamundaWriteable()).isTrue();
    assertThat(formProperty.getCamundaVariable()).isEqualTo(TEST_STRING_XML);
    assertThat(formProperty.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(formProperty.getCamundaDatePattern()).isEqualTo(TEST_STRING_XML);
    assertThat(formProperty.getCamundaDefault()).isEqualTo(TEST_STRING_XML);
    formProperty.setCamundaId(TEST_STRING_API);
    formProperty.setCamundaName(TEST_STRING_API);
    formProperty.setCamundaType(TEST_STRING_API);
    formProperty.setCamundaRequired(true);
    formProperty.setCamundaReadable(false);
    formProperty.setCamundaWriteable(false);
    formProperty.setCamundaVariable(TEST_STRING_API);
    formProperty.setCamundaExpression(TEST_EXPRESSION_API);
    formProperty.setCamundaDatePattern(TEST_STRING_API);
    formProperty.setCamundaDefault(TEST_STRING_API);
    assertThat(formProperty.getCamundaId()).isEqualTo(TEST_STRING_API);
    assertThat(formProperty.getCamundaName()).isEqualTo(TEST_STRING_API);
    assertThat(formProperty.getCamundaType()).isEqualTo(TEST_STRING_API);
    assertThat(formProperty.isCamundaRequired()).isTrue();
    assertThat(formProperty.isCamundaReadable()).isFalse();
    assertThat(formProperty.isCamundaWriteable()).isFalse();
    assertThat(formProperty.getCamundaVariable()).isEqualTo(TEST_STRING_API);
    assertThat(formProperty.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(formProperty.getCamundaDatePattern()).isEqualTo(TEST_STRING_API);
    assertThat(formProperty.getCamundaDefault()).isEqualTo(TEST_STRING_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testInExtension(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaIn in = callActivity.getExtensionElements().getElementsQuery().filterByType(CamundaIn.class).singleResult();
    assertThat(in.getCamundaSource()).isEqualTo(TEST_STRING_XML);
    assertThat(in.getCamundaSourceExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(in.getCamundaVariables()).isEqualTo(TEST_STRING_XML);
    assertThat(in.getCamundaTarget()).isEqualTo(TEST_STRING_XML);
    assertThat(in.getCamundaBusinessKey()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(in.getCamundaLocal()).isTrue();
    in.setCamundaSource(TEST_STRING_API);
    in.setCamundaSourceExpression(TEST_EXPRESSION_API);
    in.setCamundaVariables(TEST_STRING_API);
    in.setCamundaTarget(TEST_STRING_API);
    in.setCamundaBusinessKey(TEST_EXPRESSION_API);
    in.setCamundaLocal(false);
    assertThat(in.getCamundaSource()).isEqualTo(TEST_STRING_API);
    assertThat(in.getCamundaSourceExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(in.getCamundaVariables()).isEqualTo(TEST_STRING_API);
    assertThat(in.getCamundaTarget()).isEqualTo(TEST_STRING_API);
    assertThat(in.getCamundaBusinessKey()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(in.getCamundaLocal()).isFalse();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testOutExtension(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaOut out = callActivity.getExtensionElements().getElementsQuery().filterByType(CamundaOut.class).singleResult();
    assertThat(out.getCamundaSource()).isEqualTo(TEST_STRING_XML);
    assertThat(out.getCamundaSourceExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(out.getCamundaVariables()).isEqualTo(TEST_STRING_XML);
    assertThat(out.getCamundaTarget()).isEqualTo(TEST_STRING_XML);
    assertThat(out.getCamundaLocal()).isTrue();
    out.setCamundaSource(TEST_STRING_API);
    out.setCamundaSourceExpression(TEST_EXPRESSION_API);
    out.setCamundaVariables(TEST_STRING_API);
    out.setCamundaTarget(TEST_STRING_API);
    out.setCamundaLocal(false);
    assertThat(out.getCamundaSource()).isEqualTo(TEST_STRING_API);
    assertThat(out.getCamundaSourceExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(out.getCamundaVariables()).isEqualTo(TEST_STRING_API);
    assertThat(out.getCamundaTarget()).isEqualTo(TEST_STRING_API);
    assertThat(out.getCamundaLocal()).isFalse();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testPotentialStarter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaPotentialStarter potentialStarter = startEvent.getExtensionElements().getElementsQuery().filterByType(CamundaPotentialStarter.class).singleResult();
    Expression expression = potentialStarter.getResourceAssignmentExpression().getExpression();
    assertThat(expression.getTextContent()).isEqualTo(TEST_GROUPS_XML);
    expression.setTextContent(TEST_GROUPS_API);
    assertThat(expression.getTextContent()).isEqualTo(TEST_GROUPS_API);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testTaskListener(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaTaskListener taskListener = userTask.getExtensionElements().getElementsQuery().filterByType(CamundaTaskListener.class).list().get(0);
    assertThat(taskListener.getCamundaEvent()).isEqualTo(TEST_TASK_EVENT_XML);
    assertThat(taskListener.getCamundaClass()).isEqualTo(TEST_CLASS_XML);
    assertThat(taskListener.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_XML);
    assertThat(taskListener.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_XML);
    taskListener.setCamundaEvent(TEST_TASK_EVENT_API);
    taskListener.setCamundaClass(TEST_CLASS_API);
    taskListener.setCamundaExpression(TEST_EXPRESSION_API);
    taskListener.setCamundaDelegateExpression(TEST_DELEGATE_EXPRESSION_API);
    assertThat(taskListener.getCamundaEvent()).isEqualTo(TEST_TASK_EVENT_API);
    assertThat(taskListener.getCamundaClass()).isEqualTo(TEST_CLASS_API);
    assertThat(taskListener.getCamundaExpression()).isEqualTo(TEST_EXPRESSION_API);
    assertThat(taskListener.getCamundaDelegateExpression()).isEqualTo(TEST_DELEGATE_EXPRESSION_API);

    CamundaField field = taskListener.getCamundaFields().iterator().next();
    assertThat(field.getCamundaName()).isEqualTo(TEST_STRING_XML);
    assertThat(field.getCamundaString().getTextContent()).isEqualTo(TEST_STRING_XML);

    Collection<TimerEventDefinition> timeouts = taskListener.getTimeouts();
    assertThat(timeouts.size()).isEqualTo(1);

    TimerEventDefinition timeout = timeouts.iterator().next();
    assertThat(timeout.getTimeCycle()).isNull();
    assertThat(timeout.getTimeDate()).isNull();
    assertThat(timeout.getTimeDuration()).isNotNull();
    assertThat(timeout.getTimeDuration().getRawTextContent()).isEqualTo("PT1H");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaScriptTaskListener(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaTaskListener taskListener = userTask.getExtensionElements().getElementsQuery().filterByType(CamundaTaskListener.class).list().get(1);

    CamundaScript script = taskListener.getCamundaScript();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("groovy");
    assertThat(script.getCamundaResource()).isEqualTo("test.groovy");
    assertThat(script.getTextContent()).isEmpty();

    CamundaScript newScript = modelInstance.newInstance(CamundaScript.class);
    newScript.setCamundaScriptFormat("groovy");
    newScript.setTextContent("println 'Hello World'");
    taskListener.setCamundaScript(newScript);

    script = taskListener.getCamundaScript();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("groovy");
    assertThat(script.getCamundaResource()).isNull();
    assertThat(script.getTextContent()).isEqualTo("println 'Hello World'");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaModelerProperties(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaProperties camundaProperties = endEvent.getExtensionElements().getElementsQuery().filterByType(CamundaProperties.class).singleResult();
    assertThat(camundaProperties).isNotNull();
    assertThat(camundaProperties.getCamundaProperties()).hasSize(2);

    for (CamundaProperty camundaProperty : camundaProperties.getCamundaProperties()) {
      assertThat(camundaProperty.getCamundaId()).isNull();
      assertThat(camundaProperty.getCamundaName()).startsWith("name");
      assertThat(camundaProperty.getCamundaValue()).startsWith("value");
    }
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testGetNonExistingCamundaCandidateUsers(String namespace, BpmnModelInstance model) {
    setUp(model);
    userTask.removeAttributeNs(namespace, "candidateUsers");
    assertThat(userTask.getCamundaCandidateUsers()).isNull();
    assertThat(userTask.getCamundaCandidateUsersList()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testSetNullCamundaCandidateUsers(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateUsers()).isNotEmpty();
    assertThat(userTask.getCamundaCandidateUsersList()).isNotEmpty();
    userTask.setCamundaCandidateUsers(null);
    assertThat(userTask.getCamundaCandidateUsers()).isNull();
    assertThat(userTask.getCamundaCandidateUsersList()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testEmptyCamundaCandidateUsers(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateUsers()).isNotEmpty();
    assertThat(userTask.getCamundaCandidateUsersList()).isNotEmpty();
    userTask.setCamundaCandidateUsers("");
    assertThat(userTask.getCamundaCandidateUsers()).isNull();
    assertThat(userTask.getCamundaCandidateUsersList()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testSetNullCamundaCandidateUsersList(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateUsers()).isNotEmpty();
    assertThat(userTask.getCamundaCandidateUsersList()).isNotEmpty();
    userTask.setCamundaCandidateUsersList(null);
    assertThat(userTask.getCamundaCandidateUsers()).isNull();
    assertThat(userTask.getCamundaCandidateUsersList()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testEmptyCamundaCandidateUsersList(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(userTask.getCamundaCandidateUsers()).isNotEmpty();
    assertThat(userTask.getCamundaCandidateUsersList()).isNotEmpty();
    userTask.setCamundaCandidateUsersList(Collections.<String>emptyList());
    assertThat(userTask.getCamundaCandidateUsers()).isNull();
    assertThat(userTask.getCamundaCandidateUsersList()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testScriptResource(String namespace, BpmnModelInstance model) {
    setUp(model);
    assertThat(scriptTask.getScriptFormat()).isEqualTo("groovy");
    assertThat(scriptTask.getCamundaResource()).isEqualTo("test.groovy");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaConnector(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaConnector camundaConnector = serviceTask.getExtensionElements().getElementsQuery().filterByType(CamundaConnector.class).singleResult();
    assertThat(camundaConnector).isNotNull();

    CamundaConnectorId camundaConnectorId = camundaConnector.getCamundaConnectorId();
    assertThat(camundaConnectorId).isNotNull();
    assertThat(camundaConnectorId.getTextContent()).isEqualTo("soap-http-connector");

    CamundaInputOutput camundaInputOutput = camundaConnector.getCamundaInputOutput();

    Collection<CamundaInputParameter> inputParameters = camundaInputOutput.getCamundaInputParameters();
    assertThat(inputParameters).hasSize(1);

    CamundaInputParameter inputParameter = inputParameters.iterator().next();
    assertThat(inputParameter.getCamundaName()).isEqualTo("endpointUrl");
    assertThat(inputParameter.getTextContent()).isEqualTo("http://example.com/webservice");

    Collection<CamundaOutputParameter> outputParameters = camundaInputOutput.getCamundaOutputParameters();
    assertThat(outputParameters).hasSize(1);

    CamundaOutputParameter outputParameter = outputParameters.iterator().next();
    assertThat(outputParameter.getCamundaName()).isEqualTo("result");
    assertThat(outputParameter.getTextContent()).isEqualTo("output");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaInputOutput(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputOutput camundaInputOutput = serviceTask.getExtensionElements().getElementsQuery().filterByType(CamundaInputOutput.class).singleResult();
    assertThat(camundaInputOutput).isNotNull();
    assertThat(camundaInputOutput.getCamundaInputParameters()).hasSize(6);
    assertThat(camundaInputOutput.getCamundaOutputParameters()).hasSize(1);
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    // find existing
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeConstant");

    // modify existing
    inputParameter.setCamundaName("hello");
    inputParameter.setTextContent("world");
    inputParameter = findInputParameterByName(serviceTask, "hello");
    assertThat(inputParameter.getTextContent()).isEqualTo("world");

    // add new one
    inputParameter = modelInstance.newInstance(CamundaInputParameter.class);
    inputParameter.setCamundaName("abc");
    inputParameter.setTextContent("def");
    serviceTask.getExtensionElements().getElementsQuery().filterByType(CamundaInputOutput.class).singleResult()
      .addChildElement(inputParameter);

    // search for new one
    inputParameter = findInputParameterByName(serviceTask, "abc");
    assertThat(inputParameter.getCamundaName()).isEqualTo("abc");
    assertThat(inputParameter.getTextContent()).isEqualTo("def");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaNullInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeNull");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeNull");
    assertThat(inputParameter.getTextContent()).isEmpty();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaConstantInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeConstant");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeConstant");
    assertThat(inputParameter.getTextContent()).isEqualTo("foo");
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaExpressionInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeExpression");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeExpression");
    assertThat(inputParameter.getTextContent());
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaListInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeList");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeList");
    assertThat(inputParameter.getTextContent()).isNotEmpty();
    assertThat(inputParameter.getUniqueChildElementByNameNs(CAMUNDA_NS, "list")).isNotNull();

    CamundaList list = inputParameter.getValue();
    assertThat(list.getValues()).hasSize(3);
    for (BpmnModelElementInstance values : list.getValues()) {
      assertThat(values.getTextContent()).isIn("a", "b", "c");
    }

    list = modelInstance.newInstance(CamundaList.class);
    for (int i = 0; i < 4; i++) {
      CamundaValue value = modelInstance.newInstance(CamundaValue.class);
      value.setTextContent("test");
      list.getValues().add(value);
    }
    Collection<CamundaValue> testValues = Arrays.asList(modelInstance.newInstance(CamundaValue.class), modelInstance.newInstance(CamundaValue.class));
    list.getValues().addAll(testValues);
    inputParameter.setValue(list);

    list = inputParameter.getValue();
    assertThat(list.getValues()).hasSize(6);
    list.getValues().removeAll(testValues);
    ArrayList<BpmnModelElementInstance> camundaValues = new ArrayList<BpmnModelElementInstance>(list.getValues());
    assertThat(camundaValues).hasSize(4);
    for (BpmnModelElementInstance value : camundaValues) {
      assertThat(value.getTextContent()).isEqualTo("test");
    }

    list.getValues().remove(camundaValues.get(1));
    assertThat(list.getValues()).hasSize(3);

    list.getValues().removeAll(Arrays.asList(camundaValues.get(0), camundaValues.get(3)));
    assertThat(list.getValues()).hasSize(1);

    list.getValues().clear();
    assertThat(list.getValues()).isEmpty();

    // test standard list interactions
    Collection<BpmnModelElementInstance> elements = list.getValues();

    CamundaValue value = modelInstance.newInstance(CamundaValue.class);
    elements.add(value);

    List<CamundaValue> newValues = new ArrayList<CamundaValue>();
    newValues.add(modelInstance.newInstance(CamundaValue.class));
    newValues.add(modelInstance.newInstance(CamundaValue.class));
    elements.addAll(newValues);
    assertThat(elements).hasSize(3);

    assertThat(elements).doesNotContain(modelInstance.newInstance(CamundaValue.class));
    assertThat(elements.containsAll(Arrays.asList(modelInstance.newInstance(CamundaValue.class)))).isFalse();

    assertThat(elements.remove(modelInstance.newInstance(CamundaValue.class))).isFalse();
    assertThat(elements).hasSize(3);

    assertThat(elements.remove(value)).isTrue();
    assertThat(elements).hasSize(2);

    assertThat(elements.removeAll(newValues)).isTrue();
    assertThat(elements).isEmpty();

    elements.add(modelInstance.newInstance(CamundaValue.class));
    elements.clear();
    assertThat(elements).isEmpty();

    inputParameter.removeValue();
    assertThat((Object) inputParameter.getValue()).isNull();

  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaMapInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeMap");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeMap");
    assertThat(inputParameter.getTextContent()).isNotEmpty();
    assertThat(inputParameter.getUniqueChildElementByNameNs(CAMUNDA_NS, "map")).isNotNull();

    CamundaMap map = inputParameter.getValue();
    assertThat(map.getCamundaEntries()).hasSize(2);
    for (CamundaEntry entry : map.getCamundaEntries()) {
      if (entry.getCamundaKey().equals("foo")) {
        assertThat(entry.getTextContent()).isEqualTo("bar");
      }
      else {
        assertThat(entry.getCamundaKey()).isEqualTo("hello");
        assertThat(entry.getTextContent()).isEqualTo("world");
      }
    }

    map = modelInstance.newInstance(CamundaMap.class);
    CamundaEntry entry = modelInstance.newInstance(CamundaEntry.class);
    entry.setCamundaKey("test");
    entry.setTextContent("value");
    map.getCamundaEntries().add(entry);

    inputParameter.setValue(map);
    map = inputParameter.getValue();
    assertThat(map.getCamundaEntries()).hasSize(1);
    entry = map.getCamundaEntries().iterator().next();
    assertThat(entry.getCamundaKey()).isEqualTo("test");
    assertThat(entry.getTextContent()).isEqualTo("value");

    Collection<CamundaEntry> entries = map.getCamundaEntries();
    entries.add(modelInstance.newInstance(CamundaEntry.class));
    assertThat(entries).hasSize(2);

    inputParameter.removeValue();
    assertThat((Object) inputParameter.getValue()).isNull();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaScriptInputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "shouldBeScript");
    assertThat(inputParameter.getCamundaName()).isEqualTo("shouldBeScript");
    assertThat(inputParameter.getTextContent()).isNotEmpty();
    assertThat(inputParameter.getUniqueChildElementByNameNs(CAMUNDA_NS, "script")).isNotNull();
    assertThat(inputParameter.getUniqueChildElementByType(CamundaScript.class)).isNotNull();

    CamundaScript script = inputParameter.getValue();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("groovy");
    assertThat(script.getCamundaResource()).isNull();
    assertThat(script.getTextContent()).isEqualTo("1 + 1");

    script = modelInstance.newInstance(CamundaScript.class);
    script.setCamundaScriptFormat("python");
    script.setCamundaResource("script.py");

    inputParameter.setValue(script);

    script = inputParameter.getValue();
    assertThat(script.getCamundaScriptFormat()).isEqualTo("python");
    assertThat(script.getCamundaResource()).isEqualTo("script.py");
    assertThat(script.getTextContent()).isEmpty();

    inputParameter.removeValue();
    assertThat((Object) inputParameter.getValue()).isNull();
  }

  @ParameterizedTest(name = "Namespace: {0}")
  @MethodSource("parameters")
  public void testCamundaNestedOutputParameter(String namespace, BpmnModelInstance model) {
    setUp(model);
    CamundaOutputParameter camundaOutputParameter = serviceTask.getExtensionElements().getElementsQuery().filterByType(CamundaInputOutput.class).singleResult().getCamundaOutputParameters().iterator().next();

    assertThat(camundaOutputParameter).isNotNull();
    assertThat(camundaOutputParameter.getCamundaName()).isEqualTo("nested");
    CamundaList list = camundaOutputParameter.getValue();
    assertThat(list).isNotNull();
    assertThat(list.getValues()).hasSize(2);
    Iterator<BpmnModelElementInstance> iterator = list.getValues().iterator();

    // nested list
    CamundaList nestedList = (CamundaList) iterator.next().getUniqueChildElementByType(CamundaList.class);
    assertThat(nestedList).isNotNull();
    assertThat(nestedList.getValues()).hasSize(2);
    for (BpmnModelElementInstance value : nestedList.getValues()) {
      assertThat(value.getTextContent()).isEqualTo("list");
    }

    // nested map
    CamundaMap nestedMap = (CamundaMap) iterator.next().getUniqueChildElementByType(CamundaMap.class);
    assertThat(nestedMap).isNotNull();
    assertThat(nestedMap.getCamundaEntries()).hasSize(2);
    Iterator<CamundaEntry> mapIterator = nestedMap.getCamundaEntries().iterator();

    // nested list in nested map
    CamundaEntry nestedListEntry = mapIterator.next();
    assertThat(nestedListEntry).isNotNull();
    assertThat(nestedListEntry.getCamundaKey()).isEqualTo("list");
    CamundaList nestedNestedList = nestedListEntry.getValue();
    for (BpmnModelElementInstance value : nestedNestedList.getValues()) {
      assertThat(value.getTextContent()).isEqualTo("map");
    }

    // nested map in nested map
    CamundaEntry nestedMapEntry = mapIterator.next();
    assertThat(nestedMapEntry).isNotNull();
    assertThat(nestedMapEntry.getCamundaKey()).isEqualTo("map");
    CamundaMap nestedNestedMap = nestedMapEntry.getValue();
    CamundaEntry entry = nestedNestedMap.getCamundaEntries().iterator().next();
    assertThat(entry.getCamundaKey()).isEqualTo("so");
    assertThat(entry.getTextContent()).isEqualTo("nested");
  }

  protected CamundaInputParameter findInputParameterByName(BaseElement baseElement, String name) {
    Collection<CamundaInputParameter> camundaInputParameters = baseElement.getExtensionElements().getElementsQuery()
      .filterByType(CamundaInputOutput.class).singleResult().getCamundaInputParameters();
    for (CamundaInputParameter camundaInputParameter : camundaInputParameters) {
      if (camundaInputParameter.getCamundaName().equals(name)) {
        return camundaInputParameter;
      }
    }
    throw new BpmnModelException("Unable to find camunda:inputParameter with name '" + name + "' for element with id '" + baseElement.getId() + "'");
  }

}
