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
package org.cibseven.bpm.spring.boot.starter.security.oauth2.impl;

import java.util.Collections;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches if no {@code spring.security.oauth2.client.registration} properties are defined.
 * Reimplemented for Spring Boot 4 where ClientsConfiguredCondition is package-private.
 */
public class ClientsNotConfiguredCondition extends SpringBootCondition {

  private static final Bindable<Map<String, Object>> STRING_OBJECT_MAP = Bindable
      .mapOf(String.class, Object.class);

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    ConditionMessage.Builder message = ConditionMessage.forCondition("OAuth2 Clients Not Configured Condition");
    Map<String, Object> registrations = Binder.get(context.getEnvironment())
        .bind("spring.security.oauth2.client.registration", STRING_OBJECT_MAP)
        .orElse(Collections.emptyMap());
    if (registrations.isEmpty()) {
      return ConditionOutcome.match(message.notAvailable("registered clients"));
    }
    return ConditionOutcome.noMatch(message.foundExactly("registered clients"));
  }
}
