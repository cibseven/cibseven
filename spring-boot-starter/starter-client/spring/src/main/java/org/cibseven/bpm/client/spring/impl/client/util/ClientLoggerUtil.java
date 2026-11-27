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
package org.cibseven.bpm.client.spring.impl.client.util;

import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.spring.exception.SpringExternalTaskClientException;
import org.cibseven.bpm.client.spring.impl.util.LoggerUtil;

public class ClientLoggerUtil extends LoggerUtil {

  public void beanCreationSkipped(String className, String beanName) {
    logDebug("001", "Skipping creation of bean '{}' for factory '{}'. " +
            "A bean of type '{}' already exists", beanName, className, ExternalTaskClient.class);
  }

  public void registered(String beanName) {
    logDebug("002", "Registered external task client with beanName '{}'", beanName);
  }

  public void bootstrapped() {
    logDebug("003", "Client successfully bootstrapped");
  }

  public void requestInterceptorsFound(int requestInterceptorCount) {
    logDebug("004", "Found '{}' client request interceptors", requestInterceptorCount);
  }

  public void backoffStrategyFound() {
    logDebug("005", "Client backoff strategy found");
  }

  public SpringExternalTaskClientException noUniqueClientException() {
    return new SpringExternalTaskClientException(exceptionMessage(
        "006", "Multiple matching client bean candidates have been found " +
            "when only one matching client bean was expected."));
  }

  public SpringExternalTaskClientException noUniqueAnnotation() {
    return new SpringExternalTaskClientException(exceptionMessage(
        "007", "Multiple matching client annotation candidates have been found " +
            "when only one matching client annotation was expected."));
  }

  public void httpClientCustomizersFound(int httpClientCustomizerCount) {
    logDebug("008", "Found '{}' HTTP client customizers", httpClientCustomizerCount);
  }

  public SpringExternalTaskClientException httpClientTimeoutNotGreaterThanZero(String property, long value) {
    return new SpringExternalTaskClientException(exceptionMessage(
        "009", "The client property '{}' must be greater than zero, but was '{}'", property, value));
  }

  public SpringExternalTaskClientException responseTimeoutNotGreaterThanAsyncResponseTimeout(
      long responseTimeout, long asyncResponseTimeout) {
    return new SpringExternalTaskClientException(exceptionMessage(
        "010", "The client property 'responseTimeout' ({} ms) must be greater than " +
            "'asyncResponseTimeout' ({} ms), otherwise long-polling fetch and lock requests time out " +
            "on the client while the engine still holds them", responseTimeout, asyncResponseTimeout));
  }

  public void responseTimeoutMarginTooSmall(long responseTimeout, long asyncResponseTimeout, long recommendedMargin) {
    logWarn("011", "The client property 'responseTimeout' ({} ms) is less than {} ms above " +
        "'asyncResponseTimeout' ({} ms). A fetch and lock request can time out on the client just as " +
        "the engine locks a task to this worker; that task then stays locked until its lock expires",
        responseTimeout, recommendedMargin, asyncResponseTimeout);
  }

}
