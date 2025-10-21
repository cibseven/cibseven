package org.cibseven.bpm.spring.boot.starter.security.oauth2.impl;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ResourceServerConfiguredCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return context.getEnvironment().containsProperty(
                  "spring.security.oauth2.resourceserver.jwt.jwk-set-uri") ||
             context.getEnvironment().containsProperty(
                  "spring.security.oauth2.resourceserver.jwt.issuer-uri") ||
             context.getEnvironment().containsProperty(
                  "spring.security.oauth2.resourceserver.opaque-token.introspection-uri");
    }
}
