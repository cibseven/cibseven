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
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_INSTANCE_VARIABLE;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_TASK;
import static org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions.READ_TASK_VARIABLE;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.cibseven.bpm.engine.authorization.Resources.PROCESS_INSTANCE;

import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.runtime.VariableInstance;
import org.cibseven.bpm.engine.variable.VariableMap;
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
 * so {@link ProcessVariableAuthorization} has to — for the triggering user.
 *
 * <p>Runs against an engine with authorization enabled. Two shapes of process:
 * a synchronous one, where the agent task runs inside the start command under
 * the starting user's authentication, and an asynchronous one, where it runs in
 * a job without authentication and the connector falls back to the start user.
 */
public class ProcessVariableAuthorizationEngineTest {

  private static final String SYNC_KEY = "authorizationSyncTest";
  private static final String ASYNC_KEY = "authorizationAsyncTest";
  private static final String TENANT = "t1";
  private static final byte[] PDF_BYTES = "%PDF-1.7 body".getBytes();

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

  /** Reads context by default; with {@code _documents} set, attaches documents only. */
  public static class AgentDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
      StubConnector connector = new StubConnector();
      AgentRequest request = connector.createRequest()
          .agentName("authorization-agent")
          .message("What is the order about?");
      String documents = (String) execution.getVariable("_documents");
      if (documents != null) {
        request.documents(documents);
      } else {
        request.contextVariables("orderId");
      }
      connector.execute(request);
    }
  }

  @BeforeEach
  public void setUp() {
    modelCalled = false;
    deploy(null, SYNC_KEY, false);
    deploy(null, ASYNC_KEY, true);
    // What alice needs to start a process at all — deliberately nothing that
    // would let her read its variables.
    grant("alice", PROCESS_INSTANCE, ANY, CREATE);
    grant("alice", PROCESS_DEFINITION, ANY, CREATE_INSTANCE);
  }

  @AfterEach
  public void tearDown() {
    IdentityService identityService = ENGINE.getIdentityService();
    identityService.clearAuthentication();
    configuration().setEnforceSpecificVariablePermission(false);
    AuthorizationService authorizationService = ENGINE.getAuthorizationService();
    authorizationService.createAuthorizationQuery().list()
        .forEach(authorization -> authorizationService.deleteAuthorization(authorization.getId()));
    ENGINE.getRepositoryService().createDeploymentQuery().list()
        .forEach(deployment ->
            ENGINE.getRepositoryService().deleteDeployment(deployment.getId(), true));
    identityService.createTenantQuery().list()
        .forEach(tenant -> identityService.deleteTenant(tenant.getId()));
    identityService.createGroupQuery().list()
        .forEach(group -> identityService.deleteGroup(group.getId()));
    identityService.createUserQuery().list()
        .forEach(user -> identityService.deleteUser(user.getId()));
  }

  // ── synchronous: the authenticated user is the caller ──────────────────────

  @Test
  public void shouldRefuseACallerWhoCannotReadTheVariables() {
    Throwable thrown = catchThrowable(() -> startAs("alice", List.of(), SYNC_KEY, context()));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("User 'alice' may not read the variables")
        .hasMessageContaining("READ_INSTANCE or READ_TASK on process definition '"
            + SYNC_KEY + "'");
    assertThat(modelCalled).isFalse();
  }

  @Test
  public void shouldLetACallerWithReadInstanceOnTheDefinitionThrough() {
    grant("alice", PROCESS_DEFINITION, SYNC_KEY, READ_INSTANCE);

    startAs("alice", List.of(), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"GRANTED\"");
  }

  /**
   * D2: the Tasklist user who uploads a file and completes the task reads these
   * variables through TaskService with READ_TASK alone, so the agent accepts it.
   */
  @Test
  public void shouldLetACallerWithReadTaskOnTheDefinitionThrough() {
    grant("alice", PROCESS_DEFINITION, SYNC_KEY, READ_TASK);

    startAs("alice", List.of(), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
  }

  @Test
  public void shouldHonourAGrantToTheCallersGroup() {
    grantToGroup("accounting", PROCESS_DEFINITION, SYNC_KEY, READ_INSTANCE);

    startAs("alice", List.of("accounting"), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
  }

  @Test
  public void shouldHonourReadOnAnyProcessInstance() {
    grant("alice", PROCESS_INSTANCE, ANY, READ);

    startAs("alice", List.of(), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
  }

  /** A task that only attaches documents is checked just the same. */
  @Test
  public void shouldCheckATaskThatOnlyAttachesDocuments() {
    Throwable thrown = catchThrowable(() -> startAs("alice", List.of(), SYNC_KEY,
        Variables.createVariables()
            .putValue("_documents", "invoice")
            .putValueTyped("invoice", Variables.fileValue("invoice.pdf")
                .file(PDF_BYTES).mimeType("application/pdf").create())));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("User 'alice' may not read the variables");
    assertThat(modelCalled).isFalse();
  }

  @Test
  public void shouldRecordThatNothingWasCheckedWhenThereIsNoCaller() {
    ENGINE.getRuntimeService().startProcessInstanceByKey(SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"NO_CALLER\"");
  }

  /**
   * Admin membership does not replace a grant: the engine does not waive its
   * permission check for admins either.
   */
  @Test
  public void shouldNotLetAnAdminThroughWithoutAGrant() {
    createUser("root", "camunda-admin");
    grantToGroup("camunda-admin", PROCESS_INSTANCE, ANY, CREATE);
    grantToGroup("camunda-admin", PROCESS_DEFINITION, ANY, CREATE_INSTANCE);

    Throwable thrown = catchThrowable(() ->
        startAs("root", List.of("camunda-admin"), SYNC_KEY, context()));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("User 'root' may not read the variables");
  }

  // ── enforceSpecificVariablePermission ──────────────────────────────────────

  @Test
  public void shouldRequireTheVariablePermissionsWhenTheyAreEnforced() {
    configuration().setEnforceSpecificVariablePermission(true);
    grant("alice", PROCESS_DEFINITION, SYNC_KEY, READ_INSTANCE);

    Throwable thrown = catchThrowable(() -> startAs("alice", List.of(), SYNC_KEY, context()));

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("READ_INSTANCE_VARIABLE or READ_TASK_VARIABLE");
  }

  @Test
  public void shouldAcceptReadTaskVariableWhenTheVariablePermissionsAreEnforced() {
    configuration().setEnforceSpecificVariablePermission(true);
    grant("alice", PROCESS_DEFINITION, SYNC_KEY, READ_TASK_VARIABLE);

    startAs("alice", List.of(), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
  }

  @Test
  public void shouldAcceptReadInstanceVariableWhenTheVariablePermissionsAreEnforced() {
    configuration().setEnforceSpecificVariablePermission(true);
    grant("alice", PROCESS_DEFINITION, SYNC_KEY, READ_INSTANCE_VARIABLE);

    startAs("alice", List.of(), SYNC_KEY, context());

    assertThat(modelCalled).isTrue();
  }

  // ── asynchronous: the start user is the caller ─────────────────────────────

  @Test
  public void shouldCheckTheStartUserWhenTheTaskRunsAsAJob() {
    startAs("alice", List.of(), ASYNC_KEY, context());

    Throwable thrown = catchThrowable(this::executeTheJob);

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("User 'alice' may not read the variables");
    assertThat(modelCalled).isFalse();
  }

  @Test
  public void shouldLetTheStartUserThroughWithAGrant() {
    grant("alice", PROCESS_DEFINITION, ASYNC_KEY, READ_TASK);
    startAs("alice", List.of(), ASYNC_KEY, context());

    executeTheJob();

    assertThat(modelCalled).isTrue();
    assertThat(chatLog()).contains("\"authorization\":\"GRANTED\"");
  }

  /** READ on exactly this process instance, granted between start and job. */
  @Test
  public void shouldHonourReadOnTheConcreteProcessInstance() {
    ProcessInstance instance = startAs("alice", List.of(), ASYNC_KEY, context());
    grant("alice", PROCESS_INSTANCE, instance.getId(), READ);

    executeTheJob();

    assertThat(modelCalled).isTrue();
  }

  // ── tenants ────────────────────────────────────────────────────────────────

  /**
   * P1: the caller derived for a job carries the instance's tenant, so trusting
   * the Authentication would let every start user pass. Membership is read from
   * the identity service instead.
   */
  @Test
  public void shouldRefuseAStartUserWhoIsNotAMemberOfTheInstancesTenant() {
    deploy(TENANT, ASYNC_KEY, true);
    createUser("alice", null);
    ENGINE.getIdentityService().saveTenant(ENGINE.getIdentityService().newTenant(TENANT));
    grant("alice", PROCESS_DEFINITION, ASYNC_KEY, READ_INSTANCE);
    startInTenantAs("alice", List.of());

    Throwable thrown = catchThrowable(this::executeTheJob);

    assertThat(rootCause(thrown))
        .isInstanceOf(AgentConnectorException.class)
        .hasMessageContaining("tenant '" + TENANT + "'");
    assertThat(modelCalled).isFalse();
  }

  @Test
  public void shouldLetATenantMemberThrough() {
    deploy(TENANT, ASYNC_KEY, true);
    createUser("alice", null);
    ENGINE.getIdentityService().saveTenant(ENGINE.getIdentityService().newTenant(TENANT));
    ENGINE.getIdentityService().createTenantUserMembership(TENANT, "alice");
    grant("alice", PROCESS_DEFINITION, ASYNC_KEY, READ_INSTANCE);
    startInTenantAs("alice", List.of());

    executeTheJob();

    assertThat(modelCalled).isTrue();
  }

  /** As in TenantManager, admins skip the tenant check — not the permission check. */
  @Test
  public void shouldLetAnAdminWithAGrantThroughAnyTenant() {
    deploy(TENANT, ASYNC_KEY, true);
    createUser("root", "camunda-admin");
    ENGINE.getIdentityService().saveTenant(ENGINE.getIdentityService().newTenant(TENANT));
    grantToGroup("camunda-admin", PROCESS_INSTANCE, ANY, CREATE);
    grantToGroup("camunda-admin", PROCESS_DEFINITION, ANY, CREATE_INSTANCE);
    grantToGroup("camunda-admin", PROCESS_DEFINITION, ASYNC_KEY, READ_INSTANCE);
    startInTenantAs("root", List.of("camunda-admin"));

    executeTheJob();

    assertThat(modelCalled).isTrue();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static VariableMap context() {
    return Variables.createVariables().putValue("orderId", "4711");
  }

  private ProcessInstance startAs(String userId, List<String> groupIds, String key,
      VariableMap variables) {
    ENGINE.getIdentityService().setAuthentication(userId, groupIds);
    try {
      return ENGINE.getRuntimeService().startProcessInstanceByKey(key, variables);
    } finally {
      ENGINE.getIdentityService().clearAuthentication();
    }
  }

  private void startInTenantAs(String userId, List<String> groupIds) {
    ENGINE.getIdentityService().setAuthentication(userId, groupIds, List.of(TENANT));
    try {
      ENGINE.getRuntimeService().createProcessInstanceByKey(ASYNC_KEY)
          .processDefinitionTenantId(TENANT)
          .setVariables(context())
          .execute();
    } finally {
      ENGINE.getIdentityService().clearAuthentication();
    }
  }

  /** Runs the async agent task the way the job executor would: unauthenticated. */
  private void executeTheJob() {
    Job job = ENGINE.getManagementService().createJobQuery().singleResult();
    ENGINE.getManagementService().executeJob(job.getId());
  }

  private static void createUser(String userId, String groupId) {
    IdentityService identityService = ENGINE.getIdentityService();
    identityService.saveUser(identityService.newUser(userId));
    if (groupId != null) {
      if (identityService.createGroupQuery().groupId(groupId).singleResult() == null) {
        identityService.saveGroup(identityService.newGroup(groupId));
      }
      identityService.createMembership(userId, groupId);
    }
  }

  private static ProcessEngineConfigurationImpl configuration() {
    return (ProcessEngineConfigurationImpl) ENGINE.getProcessEngineConfiguration();
  }

  private static void grantToGroup(String groupId, Resource resource, String resourceId,
      Permission permission) {
    AuthorizationService authorizationService = ENGINE.getAuthorizationService();
    Authorization authorization = authorizationService.createAuthorizationQuery()
        .groupIdIn(groupId)
        .resourceType(resource)
        .resourceId(resourceId)
        .singleResult();
    if (authorization == null) {
      authorization = authorizationService.createNewAuthorization(AUTH_TYPE_GRANT);
      authorization.setGroupId(groupId);
      authorization.setResource(resource);
      authorization.setResourceId(resourceId);
    }
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

  private static void deploy(String tenantId, String key, boolean async) {
    ENGINE.getRepositoryService().createDeployment()
        .tenantId(tenantId)
        .addString(key + ".bpmn20.xml", bpmn(key, async))
        .deploy();
  }

  private static String bpmn(String key, boolean async) {
    return ""
        + "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + "             xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + "             targetNamespace='http://cibseven.org/test'>"
        + "  <process id='" + key + "' isExecutable='true'"
        + "           camunda:historyTimeToLive='1'>"
        + "    <startEvent id='start'/>"
        + "    <sequenceFlow id='f1' sourceRef='start' targetRef='agent'/>"
        + "    <serviceTask id='agent' camunda:asyncBefore='" + async + "'"
        + "                 camunda:class='" + AgentDelegate.class.getName() + "'/>"
        + "    <sequenceFlow id='f2' sourceRef='agent' targetRef='wait'/>"
        + "    <userTask id='wait'/>"
        + "    <sequenceFlow id='f3' sourceRef='wait' targetRef='end'/>"
        + "    <endEvent id='end'/>"
        + "  </process>"
        + "</definitions>";
  }
}
