/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.cibseven.bpm.engine.authorization.Authorization.ANY;
import static org.cibseven.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.cibseven.bpm.engine.authorization.Permissions.CREATE;
import static org.cibseven.bpm.engine.authorization.Permissions.CREATE_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Permissions.READ;
import static org.cibseven.bpm.engine.authorization.Permissions.READ_INSTANCE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.cibseven.connect.ai.agent.AgentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * CIB7-1843 acceptance criterion 5: a principal cannot reach process data
 * through the agent that it could not read directly. {@code contextVariables}
 * and {@code documents} read straight off the execution, which checks nothing,
 * so {@link ProcessVariableAuthorization} has to — against the engine's own
 * rule for reading process-instance variables, for the triggering user.
 *
 * <p>Runs against an engine with authorization enabled. The process is started
 * by an authenticated user, so the agent task executes synchronously under that
 * user's authentication, which is what the connector captures as the caller.
 */
public class ProcessVariableAuthorizationEngineTest {

  private static final String PROCESS_KEY = "authorizationEngineTest";

  static volatile boolean modelCalled;

  /** Built once for the whole class; see ProcessContextEngineTest for why. */
  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:variable-authorization-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setAuthorizationEnabled(true);
    return configuration.buildProcessEngine();
  }

  static final class StubChatModel implements ChatModel {
    private final List<ChatModelListener> listeners;

    StubChatModel(ChatModelListener listener) {
      this.listeners = List.of(listener);
    }

    @Override
    public List<ChatModelListener> listeners() {
      return listeners;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
      modelCalled = true;
      return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }
  }

  static final class StubConnector extends AgentConnectorImpl {
    @Override
    protected ChatModel createChatModel(AgentRequest request, String apiKey, String baseUrl,
        Map<String, String> customHeaders) {
      AgentChatListener listener =
          new AgentChatListener(request.getModel(), baseUrl, request.getPersistChatLog());
      ProcessStarterToolContext.setActiveListener(listener);
      return new StubChatModel(listener);
    }
  }

  public static class AgentDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      StubConnector connector = new StubConnector();
      connector.execute(connector.createRequest()
          .agentName("authorization-agent")
          .message("What is the order about?")
          .contextVariables("orderId"));
    }
  }

  @BeforeEach
  public void setUp() {
    modelCalled = false;
    ENGINE.getRepositoryService().createDeployment()
        .addString(PROCESS_KEY + ".bpmn20.xml", bpmn())
        .deploy();
    // What any user needs to start the process at all — deliberately nothing
    // that would let them read its variables.
    grant("alice", PROCESS_INSTANCE, ANY, CREATE);
    grant("alice", PROCESS_DEFINITION, PROCESS_KEY, CREATE_INSTANCE);
  }

  @AfterEach
  public void tearDown() {
    ENGINE.getIdentityService().clearAuthentication();
    AuthorizationService authorizationService = ENGINE.getAuthorizationService();
    authorizationService.createAuthorizationQuery().list()
        .forEach(authorization -> authorizationService.deleteAuthorization(authorization.getId()));
    ENGINE.getRepositoryService().createDeploymentQuery().list()
        .forEach(deployment ->
            ENGINE.getRepositoryService().deleteDeployment(deployment.getId(), true));
  }

  @Test
  public void shouldRefuseACallerWhoCannotReadTheVariables() {
    Throwable thrown = catchThrowable(() -> startAs("alice", List.of()));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("User 'alice' may not read the variables")
        .hasMessageContaining("READ_INSTANCE on process definition '" + PROCESS_KEY + "'");
    assertThat(modelCalled).isFalse();
  }

  @Test
  public void shouldLetACallerWithReadInstanceOnTheDefinitionThrough() {
    grant("alice", PROCESS_DEFINITION, PROCESS_KEY, READ_INSTANCE);

    startAs("alice", List.of());

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"GRANTED\"");
  }

  /** Group grants count, exactly as in the engine's own check. */
  @Test
  public void shouldHonourAGrantToTheCallersGroup() {
    grantToGroup("accounting", PROCESS_DEFINITION, PROCESS_KEY, READ_INSTANCE);

    startAs("alice", List.of("accounting"));

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"GRANTED\"");
  }

  /**
   * READ on the process instance is the engine's other way to read its
   * variables. Granted for any instance, since the id does not exist before the
   * start command that runs the agent task.
   */
  @Test
  public void shouldHonourReadOnTheProcessInstance() {
    grant("alice", PROCESS_INSTANCE, ANY, READ);

    startAs("alice", List.of());

    assertThat(modelCalled).isTrue();
  }

  /**
   * As in the engine itself, a call without an identifiable caller is not
   * checked — but the audit trail says so.
   */
  @Test
  public void shouldRecordThatNothingWasCheckedWhenThereIsNoCaller() {
    ENGINE.getRuntimeService().startProcessInstanceByKey(PROCESS_KEY,
        Variables.createVariables().putValue("orderId", "4711"));

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"NO_CALLER\"");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void startAs(String userId, List<String> groupIds) {
    ENGINE.getIdentityService().setAuthentication(userId, groupIds);
    try {
      ENGINE.getRuntimeService().startProcessInstanceByKey(PROCESS_KEY,
          Variables.createVariables().putValue("orderId", "4711"));
    } finally {
      ENGINE.getIdentityService().clearAuthentication();
    }
  }

  private static void grantToGroup(String groupId, Resource resource, String resourceId,
      Permission permission) {
    AuthorizationService authorizationService = ENGINE.getAuthorizationService();
    Authorization authorization = authorizationService.createNewAuthorization(AUTH_TYPE_GRANT);
    authorization.setGroupId(groupId);
    authorization.setResource(resource);
    authorization.setResourceId(resourceId);
    authorization.addPermission(permission);
    authorizationService.saveAuthorization(authorization);
  }

  private static void grant(String userId, Resource resource, String resourceId,
      Permission permission) {
    AuthorizationService authorizationService = ENGINE.getAuthorizationService();
    // One grant per user and resource is all the schema allows, so extend it.
    Authorization authorization = authorizationService.createAuthorizationQuery()
        .userIdIn(userId)
        .resourceType(resource)
        .resourceId(resourceId)
        .singleResult();
    if (authorization == null) {
      authorization = authorizationService.createNewAuthorization(AUTH_TYPE_GRANT);
      authorization.setUserId(userId);
      authorization.setResource(resource);
      authorization.setResourceId(resourceId);
    }
    authorization.addPermission(permission);
    authorizationService.saveAuthorization(authorization);
  }

  private static String chatLog() {
    List<VariableInstance> variables = ENGINE.getRuntimeService()
        .createVariableInstanceQuery()
        .variableNameLike(AgentConnectorConstants.AGENT_CONNECTOR_LOG_PREFIX + "%")
        .list();
    assertThat(variables).as("chat-log variable").hasSize(1);
    return (String) variables.get(0).getValue();
  }

  private static Throwable rootCause(Throwable thrown) {
    assertThat(thrown).as("expected the activity to fail").isNotNull();
    Throwable current = thrown;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private static String bpmn() {
    return ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='" + PROCESS_KEY + "' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent'/>"
        + "    <serviceTask id='agent'"
        + "                 camunda:class='" + AgentDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        + "    <userTask id='wait'/>"
        + "    <sequenceFlow id='f3' sourceRef='wait' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";
  }
}
