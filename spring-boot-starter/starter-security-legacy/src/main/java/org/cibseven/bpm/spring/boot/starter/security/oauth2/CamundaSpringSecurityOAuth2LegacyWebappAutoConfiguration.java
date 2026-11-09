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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.Map;

import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.ClientsConfiguredCondition;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.OAuth2AuthenticationProvider;
import org.cibseven.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Authenticates the requests to the legacy webapps (Cockpit, Tasklist, Admin) with the OAuth2 login
 * of {@link CamundaSpringSecurityOAuth2WebappAutoConfiguration}. This module is never published: it
 * only serves the tests that still drive the engine through the legacy webapps.
 */
@AutoConfigureAfter(CamundaSpringSecurityOAuth2WebappAutoConfiguration.class)
@ConditionalOnBean(CamundaBpmProperties.class)
@Conditional(ClientsConfiguredCondition.class)
@ConditionalOnClass(name = "org.cibseven.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter")
@Configuration
public class CamundaSpringSecurityOAuth2LegacyWebappAutoConfiguration {

  @Bean
  public FilterRegistrationBean<?> webappAuthenticationFilter(CamundaBpmProperties properties) {
    String legacyWebappPath = properties.getWebapp().getLegacyApplicationPath();
    FilterRegistrationBean<Filter> filterRegistration = new FilterRegistrationBean<>();
    filterRegistration.setName("Container Based Authentication Filter for legacy webapp");
    filterRegistration.setFilter(new ContainerBasedAuthenticationFilter());
    filterRegistration.setInitParameters(Map.of(
        ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM, OAuth2AuthenticationProvider.class.getName()));
    // make sure the filter is registered after the Spring Security Filter Chain
    filterRegistration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
    filterRegistration.addUrlPatterns(legacyWebappPath + "/app/*", legacyWebappPath + "/api/*");
    filterRegistration.setDispatcherTypes(DispatcherType.REQUEST);
    return filterRegistration;
  }
}
