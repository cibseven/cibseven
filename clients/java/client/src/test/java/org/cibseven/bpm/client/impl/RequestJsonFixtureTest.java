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
package org.cibseven.bpm.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.cibseven.bpm.client.variable.json.fixture.JsonFixtures.assertMatchesFixture;
import static org.cibseven.bpm.client.variable.json.fixture.JsonFixtures.readString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.cibseven.bpm.client.exception.RestException;
import org.cibseven.bpm.client.task.ExternalTask;
import org.cibseven.bpm.client.task.impl.ExternalTaskImpl;
import org.cibseven.bpm.client.topic.impl.dto.TopicRequestDto;
import org.cibseven.bpm.client.variable.ClientValues;
import org.cibseven.bpm.client.variable.impl.TypedValueField;
import org.cibseven.bpm.client.variable.impl.VariableValue;
import org.cibseven.bpm.client.variable.json.JsonVariableFixtureTest;
import org.cibseven.bpm.client.variable.json.fixture.FixtureItem;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON the client sends to and reads from the engine REST API (fetch and lock, complete, failure,
 * BPMN error, lock handling, variables, error responses). The request fixtures were recorded with the Jackson 2
 * based client and must stay byte-identical.
 */
public class RequestJsonFixtureTest {

  protected static final String BASE_URL = "http://localhost:8080/engine-rest";

  protected TimeZone defaultTimeZone;
  protected List<ClassicHttpRequest> requests;
  protected List<String> requestBodies;
  protected List<BasicClassicHttpResponse> responses;
  protected FixtureClientBuilder clientBuilder;
  protected EngineClient engineClient;

  @BeforeEach
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void setUp() throws Exception {
    // date variables and the REST date format depend on the default time zone
    defaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

    requests = new ArrayList<>();
    requestBodies = new ArrayList<>();
    responses = new ArrayList<>();

    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
      ClassicHttpRequest request = invocation.getArgument(0);
      HttpClientResponseHandler handler = invocation.getArgument(1);
      requests.add(request);
      requestBodies.add(request.getEntity() == null ? null : EntityUtils.toString(request.getEntity(), StandardCharsets.UTF_8));
      BasicClassicHttpResponse response = responses.isEmpty() ? new BasicClassicHttpResponse(204) : responses.remove(0);
      return handler.handleResponse(response);
    });

    clientBuilder = new FixtureClientBuilder(httpClient);
    clientBuilder.baseUrl(BASE_URL)
        .workerId("fixture-worker")
        .maxTasks(5)
        .asyncResponseTimeout(30_000)
        .lockDuration(10_000)
        .usePriority(false)
        .orderByCreateTime().desc()
        .disableAutoFetching();
    clientBuilder.build();
    engineClient = clientBuilder.getEngineClient();
  }

  @AfterEach
  public void tearDown() {
    TimeZone.setDefault(defaultTimeZone);
  }

  // ---------------------------------------------------------------------------------------------------------------
  // requests
  // ---------------------------------------------------------------------------------------------------------------

  @Test
  public void shouldSendFetchAndLock() {
    // given
    TopicRequestDto invoice = new TopicRequestDto("invoice", 10_000, List.of("amount", "customer"), "bk-1");
    invoice.setLocalVariables(true);
    invoice.setProcessDefinitionId("invoice:1:4711");
    invoice.setProcessDefinitionIdIn(List.of("invoice:1:4711", "invoice:2:4712"));
    invoice.setProcessDefinitionKey("invoice");
    invoice.setProcessDefinitionKeyIn(List.of("invoice", "invoice-ext"));
    invoice.setProcessDefinitionVersionTag("v1");
    Map<String, Object> processVariables = new LinkedHashMap<>();
    processVariables.put("region", "EU");
    processVariables.put("amount", 100);
    processVariables.put("approved", true);
    invoice.setProcessVariables(processVariables);
    invoice.setTenantIdIn(List.of("tenant-a", "tenant-b"));
    invoice.setIncludeExtensionProperties(true);
    TopicRequestDto shipping = new TopicRequestDto("shipping", 5_000, null, null);
    shipping.setWithoutTenantId(true);

    respondWith(200, "[]");

    // when
    List<ExternalTask> tasks = engineClient.fetchAndLock(List.of(invoice, shipping));

    // then
    assertThat(tasks).isEmpty();
    assertRequest("POST", "/external-task/fetchAndLock", "requests/fetch-and-lock.json");
  }

  @Test
  public void shouldSendFetchAndLockWithoutOptionalFields() {
    // given
    FixtureClientBuilder builder = new FixtureClientBuilder(clientBuilder.httpClient);
    builder.baseUrl(BASE_URL).workerId("fixture-worker").disableAutoFetching().build();
    respondWith(200, "[]");

    // when
    builder.getEngineClient().fetchAndLock(List.of(new TopicRequestDto("minimal", 1_000, null, null)));

    // then
    assertRequest("POST", "/external-task/fetchAndLock", "requests/fetch-and-lock-minimal.json");
  }

  @Test
  public void shouldSendComplete() {
    // when
    engineClient.complete("task-1", variables(), Map.of("localFlag", true));

    // then
    assertRequest("POST", "/external-task/task-1/complete", "requests/complete.json");
  }

  @Test
  public void shouldSendCompleteWithoutVariables() {
    // when
    engineClient.complete("task-1", null, null);

    // then
    assertRequest("POST", "/external-task/task-1/complete", "requests/complete-empty.json");
  }

  @Test
  public void shouldSendFailure() {
    // when
    engineClient.failure("task-2", "Something went wrong: \"quoted\"", "java.lang.IllegalStateException: boom\n\tat a.b.C.d(C.java:1)",
        3, 5_000L, Map.of("attempt", 2), Map.of("reason", "timeout"));

    // then
    assertRequest("POST", "/external-task/task-2/failure", "requests/failure.json");
  }

  @Test
  public void shouldSendBpmnError() {
    // when
    engineClient.bpmnError("task-3", "ERR_42", "Business error ä/ö", Map.of("errorItem", new FixtureItem("E-1", 1)));

    // then
    assertRequest("POST", "/external-task/task-3/bpmnError", "requests/bpmn-error.json");
  }

  @Test
  public void shouldSendSetVariables() {
    // when
    engineClient.setVariables("process-instance-1", Map.of("item", new FixtureItem("V-1", 4)));

    // then
    assertRequest("POST", "/process-instance/process-instance-1/variables", "requests/set-variables.json");
  }

  @Test
  public void shouldSendLockHandling() {
    // when
    engineClient.lock("task-4", 15_000L);
    engineClient.extendLock("task-4", 25_000L);
    engineClient.unlock("task-4");

    // then
    assertThat(requests).extracting(r -> r.getRequestUri()).containsExactly(
        "/engine-rest/external-task/task-4/lock",
        "/engine-rest/external-task/task-4/extendLock",
        "/engine-rest/external-task/task-4/unlock");
    assertMatchesFixture("requests/lock.json", requestBodies.get(0));
    assertMatchesFixture("requests/extend-lock.json", requestBodies.get(1));
    assertMatchesFixture("requests/unlock.json", String.valueOf(requestBodies.get(2)));
  }

  // ---------------------------------------------------------------------------------------------------------------
  // responses
  // ---------------------------------------------------------------------------------------------------------------

  @Test
  public void shouldReadFetchAndLockResponse() {
    // given
    respondWith(200, readString("responses/fetch-and-lock.json"));

    // when
    List<ExternalTask> tasks = engineClient.fetchAndLock(List.of(new TopicRequestDto("invoice", 1_000, null, null)));

    // then
    StringBuilder dump = new StringBuilder();
    for (ExternalTask task : tasks) {
      dump(dump, (ExternalTaskImpl) task);
    }
    assertMatchesFixture("responses/fetch-and-lock.dump.txt", dump.toString());

    ExternalTaskImpl task = (ExternalTaskImpl) tasks.get(0);
    assertThat(task.getLockExpirationTime()).isEqualTo(Date.from(Instant.parse("2026-11-07T11:34:56.789Z")));
    assertThat(task.getCreateTime()).isEqualTo(Date.from(Instant.parse("2026-11-07T09:00:00.000Z")));
    assertThat((Object) task.getVariable("order")).isEqualTo(new FixtureItem("A-1", 2));
  }

  @Test
  public void shouldReadEngineError() {
    // given
    respondWith(500, "{\"type\":\"ProcessEngineException\",\"message\":\"boom\",\"code\":22222,\"someFutureField\":{\"x\":1}}");

    // when
    Throwable thrown = catchThrowable(() -> engineClient.complete("task-1", null, null));

    // then
    assertThat(thrown).isInstanceOf(EngineClientException.class);
    assertThat(thrown.getMessage()).contains("status code '500'").contains("boom");
    assertThat(thrown.getCause()).isInstanceOf(RestException.class);
    RestException restException = (RestException) thrown.getCause();
    assertThat(restException.getHttpStatusCode()).isEqualTo(500);
    assertThat(restException.getType()).isEqualTo("ProcessEngineException");
    assertThat(restException.getCode()).isEqualTo(22222);
    assertThat(restException.getMessage()).isEqualTo("boom");
  }

  @Test
  public void shouldReadEngineErrorWithoutBody() {
    // given
    responses.add(new BasicClassicHttpResponse(404));

    // when
    Throwable thrown = catchThrowable(() -> engineClient.complete("task-1", null, null));

    // then
    assertThat(thrown).isInstanceOf(EngineClientException.class);
    assertThat(((RestException) thrown.getCause()).getHttpStatusCode()).isEqualTo(404);
  }

  @Test
  public void shouldReportResponsesThatAreNoJson() {
    // given
    respondWith(500, "<html><body>Bad Gateway</body></html>");
    respondWith(200, "[{\"id\":{\"nested\":1}}]");
    respondWith(200, "{\"id\":\"not-an-array\"}");

    // when
    Throwable htmlError = catchThrowable(() -> engineClient.complete("task-1", null, null));
    Throwable wrongType = catchThrowable(() -> engineClient.fetchAndLock(List.of()));
    Throwable noArray = catchThrowable(() -> engineClient.fetchAndLock(List.of()));

    // then
    StringBuilder dump = new StringBuilder();
    dump.append("htmlError: ").append(describe(htmlError)).append('\n');
    dump.append("wrongType: ").append(describe(wrongType)).append('\n');
    dump.append("noArray: ").append(describe(noArray)).append('\n');
    assertMatchesFixture("responses/invalid-json.txt", dump.toString());
  }

  /**
   * Malformed JSON after the first token: Jackson 2 wraps the parser error of an array element into a mapping
   * error (TASK/CLIENT-02005), Jackson 3 keeps it a parser error (TASK/CLIENT-02004). Both are reported as
   * {@link EngineClientException}.
   */
  @Test
  public void shouldReportTruncatedResponses() {
    // given
    respondWith(200, "[{\"id\":");
    respondWith(200, "[{\"id\":\"ab");
    respondWith(200, "[{\"id\":tr");

    for (int i = 0; i < 3; i++) {
      // when
      Throwable thrown = catchThrowable(() -> engineClient.fetchAndLock(List.of()));

      // then
      assertThat(thrown).isInstanceOf(EngineClientException.class)
          .hasMessageMatching("TASK/CLIENT-0200[45] Exception while (parsing|mapping) json object to response dto class "
              + "'class \\[Lorg.cibseven.bpm.client.task.impl.ExternalTaskImpl;'");
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Same as the regular builder, but sends the requests to the given (mocked) HTTP client.
   */
  static class FixtureClientBuilder extends ExternalTaskClientBuilderImpl {

    protected final HttpClient httpClient;

    FixtureClientBuilder(HttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    protected void initEngineClient() {
      RequestExecutor requestExecutor = new RequestExecutor(httpClient, objectMapper);
      engineClient = new EngineClient(workerId, maxTasks, asyncResponseTimeout, urlResolver, requestExecutor, usePriority,
          orderingConfig);
    }
  }

  protected void respondWith(int status, String json) {
    BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
    response.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
    responses.add(response);
  }

  protected void assertRequest(String method, String path, String fixture) {
    assertThat(requests).hasSize(1);
    ClassicHttpRequest request = requests.get(0);
    assertThat(request.getMethod()).isEqualTo(method);
    assertThat(request.getRequestUri()).isEqualTo("/engine-rest" + path);
    assertThat(request.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue()).isEqualTo("application/json");
    assertMatchesFixture(fixture, requestBodies.get(0));
  }

  protected static Map<String, Object> variables() {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("string", "Hello \"world\" / ä");
    variables.put("integer", 42);
    variables.put("long", 9_007_199_254_740_993L);
    variables.put("short", (short) 7);
    variables.put("double", 0.1);
    variables.put("boolean", true);
    variables.put("null", null);
    variables.put("date", Date.from(Instant.parse("2026-11-07T11:22:33.444Z")));
    variables.put("bytes", "bin".getBytes(StandardCharsets.UTF_8));
    variables.put("json", ClientValues.jsonValue("{\"a\": [1, 2]}"));
    variables.put("xml", ClientValues.xmlValue("<a>1</a>"));
    variables.put("bean", JsonVariableFixtureTest.fullBean());
    variables.put("list", new ArrayList<>(List.of(new FixtureItem("L-1", 1), new FixtureItem("L-2", 2))));
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("k1", "v1");
    map.put("k2", 2);
    variables.put("map", map);
    variables.put("typedObject", Variables.objectValue(new FixtureItem("T-1", 9))
        .serializationDataFormat(Variables.SerializationDataFormats.JSON).create());
    variables.put("serializedObject", Variables.serializedObjectValue("{\"sku\":\"S-1\",\"qty\":1}")
        .objectTypeName(FixtureItem.class.getName())
        .serializationDataFormat(Variables.SerializationDataFormats.JSON).create());
    variables.put("transientString", Variables.stringValue("transient", true));
    variables.put("typedNullInteger", Variables.integerValue(null));
    variables.put("file", Variables.fileValue("hello.txt")
        .file("hi".getBytes(StandardCharsets.UTF_8))
        .mimeType("text/plain")
        .encoding("UTF-8")
        .create());
    return variables;
  }

  protected void dump(StringBuilder dump, ExternalTaskImpl task) {
    // what the topic subscription manager does with the received variables
    Map<String, VariableValue> wrapped = clientBuilder.getTypedValues().wrapVariables(task, task.getVariables());
    task.setReceivedVariableMap(wrapped);

    dump.append("task ").append(task.getId()).append('\n');
    dump.append("  activityId=").append(task.getActivityId()).append('\n');
    dump.append("  activityInstanceId=").append(task.getActivityInstanceId()).append('\n');
    dump.append("  errorMessage=").append(task.getErrorMessage()).append('\n');
    dump.append("  errorDetails=").append(task.getErrorDetails()).append('\n');
    dump.append("  executionId=").append(task.getExecutionId()).append('\n');
    dump.append("  lockExpirationTime=").append(time(task.getLockExpirationTime())).append('\n');
    dump.append("  createTime=").append(time(task.getCreateTime())).append('\n');
    dump.append("  processDefinitionId=").append(task.getProcessDefinitionId()).append('\n');
    dump.append("  processDefinitionKey=").append(task.getProcessDefinitionKey()).append('\n');
    dump.append("  processDefinitionVersionTag=").append(task.getProcessDefinitionVersionTag()).append('\n');
    dump.append("  processInstanceId=").append(task.getProcessInstanceId()).append('\n');
    dump.append("  retries=").append(task.getRetries()).append('\n');
    dump.append("  workerId=").append(task.getWorkerId()).append('\n');
    dump.append("  topicName=").append(task.getTopicName()).append('\n');
    dump.append("  tenantId=").append(task.getTenantId()).append('\n');
    dump.append("  priority=").append(task.getPriority()).append('\n');
    dump.append("  businessKey=").append(task.getBusinessKey()).append('\n');
    dump.append("  extensionProperties=").append(task.getExtensionProperties() == null ? null
        : new TreeMap<>(task.getExtensionProperties())).append('\n');
    if (task.getVariables() != null) {
      new TreeMap<>(task.getVariables()).forEach((name, field) -> dumpVariable(dump, task, name, field));
    }
  }

  protected void dumpVariable(StringBuilder dump, ExternalTaskImpl task, String name, TypedValueField field) {
    Object raw = field.getValue();
    dump.append("  variable ").append(name)
        .append(" type=").append(field.getType())
        .append(" raw=").append(raw == null ? "null" : raw.getClass().getSimpleName() + "(" + raw + ")")
        .append(" valueInfo=").append(field.getValueInfo() == null ? null : new TreeMap<>(field.getValueInfo()));
    Object value;
    try {
      value = task.getVariable(name);
      dump.append(" value=").append(value == null ? "null" : value.getClass().getSimpleName() + "(" + render(value) + ")");
    } catch (RuntimeException e) {
      dump.append(" value=FAIL ").append(e.getClass().getSimpleName());
    }
    dump.append('\n');
  }

  protected static String render(Object value) {
    if (value instanceof Date) {
      return time((Date) value);
    }
    if (value instanceof byte[]) {
      return new String((byte[]) value, StandardCharsets.UTF_8);
    }
    return String.valueOf(value);
  }

  protected static String time(Date date) {
    return date == null ? "null" : date.getTime() + " (" + date.toInstant() + ")";
  }

  protected static String describe(Throwable thrown) {
    if (thrown == null) {
      return "no exception";
    }
    String message = String.valueOf(thrown.getMessage());
    // the logger message id and text, without the (library specific) cause details
    int causeStart = message.indexOf(" - message: ");
    String shortMessage = causeStart < 0 ? message : message.substring(0, causeStart);
    return thrown.getClass().getSimpleName() + ": " + shortMessage;
  }

}
