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

import jakarta.annotation.Nullable;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.property.WebappProperty;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.AuthorizeTokenFilter;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.OAuth2AuthenticationProvider;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.SsoLogoutSuccessHandler;
import org.cibseven.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.oauth2.client.ClientsConfiguredCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

@AutoConfigureOrder(CamundaSpringSecurityOAuth2CommonAutoConfiguration.CAMUNDA_OAUTH2_ORDER + 2)
@AutoConfigureAfter(CamundaSpringSecurityOAuth2CommonAutoConfiguration.class)
@ConditionalOnBean(CamundaBpmProperties.class)
@Conditional(ClientsConfiguredCondition.class)
@EnableConfigurationProperties(OAuth2Properties.class)
@Configuration
public class CamundaSpringSecurityOAuth2WebappAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaSpringSecurityOAuth2WebappAutoConfiguration.class);
  private final OAuth2Properties oAuth2Properties;
  private final String legacyWebappPath;

  public CamundaSpringSecurityOAuth2WebappAutoConfiguration(CamundaBpmProperties properties,
                                                      OAuth2Properties oAuth2Properties) {
    this.oAuth2Properties = oAuth2Properties;
    this.legacyWebappPath = properties.getWebapp().getLegacyApplicationPath();
  }

  @Bean
  @ConditionalOnProperty(name = "sso-logout.enabled", havingValue = "true", prefix = OAuth2Properties.PREFIX)
  protected SsoLogoutSuccessHandler ssoLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
    logger.debug("Registering SsoLogoutSuccessHandler");
    return new SsoLogoutSuccessHandler(clientRegistrationRepository, oAuth2Properties);
  } 

  @Bean
  public FilterRegistrationBean<?> webappAuthenticationFilter() {
    FilterRegistrationBean<Filter> filterRegistration = new FilterRegistrationBean<>();
    filterRegistration.setName("Container Based Authentication Filter for legacy webapp");
    filterRegistration.setFilter(new ContainerBasedAuthenticationFilter());
    filterRegistration.setInitParameters(Map.of(
        ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM, OAuth2AuthenticationProvider.class.getName()));
    // make sure the filter is registered after the Spring Security Filter Chain
    filterRegistration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
    filterRegistration.addUrlPatterns(legacyWebappPath + "/app/*", legacyWebappPath + "/api/*");
    filterRegistration.setDispatcherTypes(DispatcherType.REQUEST);
    return filterRegistration;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain webappSecurityFilterChain(HttpSecurity http,
                                                       OAuth2AuthorizedClientManager clientManager,
                                                       @Nullable SsoLogoutSuccessHandler ssoLogoutSuccessHandler) throws Exception {
      logger.info("Enabling Camunda Spring Security oauth2 integration for legacy webapp");

      // @formatter:off
      http.securityMatcher(request -> {
            String fullPath = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
            // customized login base URIs for OIDC are not supported
            return fullPath.startsWith(legacyWebappPath) || fullPath.startsWith(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
          })
          .authorizeHttpRequests(c -> c
            .requestMatchers(legacyWebappPath + "/app/**").authenticated()
            .requestMatchers(legacyWebappPath + "/api/**").authenticated())
          .addFilterAfter(new AuthorizeTokenFilter(clientManager), OAuth2AuthorizationRequestRedirectFilter.class)
          .anonymous(AbstractHttpConfigurer::disable)
          .oidcLogout(c -> c.backChannel(Customizer.withDefaults()))
          .oauth2Login(Customizer.withDefaults())
          .logout(c -> c
              .clearAuthentication(true)
              .invalidateHttpSession(true)
          )
          .oauth2Client(Customizer.withDefaults())
          .cors(AbstractHttpConfigurer::disable)
          .csrf(AbstractHttpConfigurer::disable);
      // @formatter:on

      if (oAuth2Properties.getSsoLogout().isEnabled()) {
          http.logout(c -> c.logoutSuccessHandler(ssoLogoutSuccessHandler));
      }

      return http.build();
  }
}
