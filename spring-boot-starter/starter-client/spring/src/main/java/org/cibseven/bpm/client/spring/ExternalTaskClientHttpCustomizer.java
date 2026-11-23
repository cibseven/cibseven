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
package org.cibseven.bpm.client.spring;

import java.util.function.Consumer;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;

/**
 * Customizes the Apache HttpClient that the external task client uses to call the engine.
 *
 * <p>Every bean of this type in the application context is passed to
 * {@link ExternalTaskClientBuilder#customizeHttpClient(Consumer)} when the client is built,
 * in the order given by {@code @Order} or {@link org.springframework.core.Ordered}.
 *
 * <p>Example, a response timeout for the client's calls to the engine:
 * <pre>
 * &#64;Bean
 * public ExternalTaskClientHttpCustomizer responseTimeout() {
 *   return builder -&gt; builder.setDefaultRequestConfig(RequestConfig.custom()
 *       .setResponseTimeout(Timeout.ofSeconds(30))
 *       .build());
 * }
 * </pre>
 *
 * <p>A response timeout also applies to the long-polling fetch and lock request. Keep it
 * greater than the client's {@code asyncResponseTimeout}, otherwise every long poll fails.
 *
 * <p>{@code setDefaultRequestConfig} replaces the whole request configuration, so when several
 * customizers set it, the last one wins.
 */
@FunctionalInterface
public interface ExternalTaskClientHttpCustomizer extends Consumer<HttpClientBuilder> {
}
