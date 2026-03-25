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
 * Condition that matches if any {@code spring.security.oauth2.client.registration} properties are defined.
 * Reimplemented for Spring Boot 4 where the original ClientsConfiguredCondition is package-private.
 */
public class ClientsConfiguredCondition extends SpringBootCondition {

  private static final Bindable<Map<String, Object>> STRING_OBJECT_MAP = Bindable
      .mapOf(String.class, Object.class);

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    ConditionMessage.Builder message = ConditionMessage.forCondition("OAuth2 Clients Configured Condition");
    Map<String, Object> registrations = Binder.get(context.getEnvironment())
        .bind("spring.security.oauth2.client.registration", STRING_OBJECT_MAP)
        .orElse(Collections.emptyMap());
    if (!registrations.isEmpty()) {
      return ConditionOutcome.match(message.foundExactly("registered clients"));
    }
    return ConditionOutcome.noMatch(message.notAvailable("registered clients"));
  }
}
