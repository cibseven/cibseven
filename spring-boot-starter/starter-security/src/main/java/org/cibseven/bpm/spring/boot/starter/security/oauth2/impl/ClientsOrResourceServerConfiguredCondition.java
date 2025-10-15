package org.cibseven.bpm.spring.boot.starter.security.oauth2.impl;

import org.springframework.boot.autoconfigure.security.oauth2.client.ClientsConfiguredCondition;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ClientsOrResourceServerConfiguredCondition implements Condition {
	
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    boolean clientsConfigured = new ClientsConfiguredCondition().matches(context, metadata);

    boolean resourceServerConfigured = new ResourceServerConfiguredCondition().matches(context, metadata);
    
    return clientsConfigured || resourceServerConfigured;
  }
}
