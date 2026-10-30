package org.cibseven.bpm.spring.boot.starter.security.oauth2.impl;

import java.util.Collections;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches if OAuth2 clients or resource server properties are configured.
 * Reimplemented for Spring Boot 4 where ClientsConfiguredCondition is package-private.
 */
public class ClientsOrResourceServerConfiguredCondition implements Condition {

  private static final Bindable<Map<String, Object>> STRING_OBJECT_MAP = Bindable
      .mapOf(String.class, Object.class);

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    Map<String, Object> clientRegistrations = Binder.get(context.getEnvironment())
        .bind("spring.security.oauth2.client.registration", STRING_OBJECT_MAP)
        .orElse(Collections.emptyMap());
    boolean clientsConfigured = !clientRegistrations.isEmpty();

    boolean resourceServerConfigured = new ResourceServerConfiguredCondition().matches(context, metadata);

    return clientsConfigured || resourceServerConfigured;
  }
}
