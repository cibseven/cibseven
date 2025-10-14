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
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.ClientsNotConfiguredCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Conditional(ClientsNotConfiguredCondition.class)
@ConditionalOnBean(CamundaBpmProperties.class)
@Configuration
public class CamundaBpmSpringSecurityDisableAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaBpmSpringSecurityDisableAutoConfiguration.class);
  private final String legacyWebappPath;

  public CamundaBpmSpringSecurityDisableAutoConfiguration(CamundaBpmProperties properties) {
    this.legacyWebappPath = properties.getWebapp().getLegacyApplicationPath();
  }
  
  @Bean
  public SecurityFilterChain filterChainWebappPermitAll(HttpSecurity http) throws Exception {
    logger.info("Disabling Camunda Spring Security oauth2 integration for legacy webapp");

    // @formatter:off
    http.securityMatcher(request -> {
          String fullPath = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
          return fullPath.startsWith(legacyWebappPath + "/app/") || fullPath.startsWith(legacyWebappPath + "/api/");
        })
        .authorizeHttpRequests(customizer -> customizer.anyRequest().permitAll())
        .cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable);
    // @formatter:on
    return http.build();
  }

}
