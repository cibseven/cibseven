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
package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keeps the default security filter chains of Spring Boot off, which would put the whole application
 * behind a login (form login, or OAuth2 login once clients are registered). The engine REST API is
 * secured by {@link CamundaSpringSecurityOAuth2EngineAutoConfiguration} when a resource server is
 * configured, and the webclient brings its own authentication, so this chain matches no request.
 */
@ConditionalOnBean(CamundaBpmProperties.class)
@Configuration
public class CamundaBpmSpringSecurityDisableAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaBpmSpringSecurityDisableAutoConfiguration.class);

  @Bean
  public SecurityFilterChain noDefaultSecurityFilterChain(HttpSecurity http) throws Exception {
    logger.debug("Disabling the default Spring Security filter chains");
    return http.securityMatcher(request -> false).build();
  }

}
