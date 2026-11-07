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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.topic.impl.dto.TopicRequestDto;
import org.junit.jupiter.api.Test;

/**
 * The {@code sorting} of the fetch and lock request: each configured field once (CIB7-2631).
 */
public class FetchAndLockSortingTest {

  protected static final String DESC = "\"sorting\":[{\"sortBy\":\"createTime\",\"sortOrder\":\"desc\"}]";
  protected static final String ASC = "\"sorting\":[{\"sortBy\":\"createTime\",\"sortOrder\":\"asc\"}]";

  @Test
  public void shouldSendOrderByCreateTimeDescOnce() throws Exception {
    assertThat(fetchAndLockBody(builder -> builder.orderByCreateTime().desc())).contains(DESC);
  }

  @Test
  public void shouldSendOrderByCreateTimeAscOnce() throws Exception {
    assertThat(fetchAndLockBody(builder -> builder.orderByCreateTime().asc())).contains(ASC);
  }

  @Test
  public void shouldSendUseCreateTimeOnce() throws Exception {
    assertThat(fetchAndLockBody(builder -> builder.useCreateTime(true))).contains(DESC);
  }

  @Test
  public void shouldSendEmptySortingByDefault() throws Exception {
    assertThat(fetchAndLockBody(builder -> { })).contains("\"sorting\":[]");
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  protected String fetchAndLockBody(Consumer<ExternalTaskClientBuilder> ordering) throws Exception {
    List<String> bodies = new ArrayList<>();
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
      ClassicHttpRequest request = invocation.getArgument(0);
      bodies.add(EntityUtils.toString(request.getEntity(), StandardCharsets.UTF_8));
      BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
      response.setEntity(new StringEntity("[]", ContentType.APPLICATION_JSON));
      return ((HttpClientResponseHandler) invocation.getArgument(1)).handleResponse(response);
    });

    CapturingClientBuilder builder = new CapturingClientBuilder(httpClient);
    builder.baseUrl("http://localhost:8080/engine-rest").workerId("worker").disableAutoFetching();
    ordering.accept(builder);
    builder.build();

    builder.getEngineClient().fetchAndLock(List.of(new TopicRequestDto("topic", 1_000, null, null)));

    assertThat(bodies).hasSize(1);
    return bodies.get(0);
  }

  static class CapturingClientBuilder extends ExternalTaskClientBuilderImpl {

    protected final HttpClient httpClient;

    CapturingClientBuilder(HttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    protected void initEngineClient() {
      RequestExecutor requestExecutor = new RequestExecutor(httpClient, objectMapper);
      engineClient = new EngineClient(workerId, maxTasks, asyncResponseTimeout, urlResolver, requestExecutor, usePriority,
          orderingConfig);
    }
  }

}
