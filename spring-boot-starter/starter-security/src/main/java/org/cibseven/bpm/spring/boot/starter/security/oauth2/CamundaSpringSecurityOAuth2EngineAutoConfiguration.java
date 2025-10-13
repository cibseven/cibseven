package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import java.util.Map;

import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaBpmRestInitializer;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.CompositeOAuth2AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.web.servlet.JerseyApplicationPath;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

@AutoConfigureOrder(CamundaSpringSecurityOAuth2CommonAutoConfiguration.CAMUNDA_OAUTH2_ORDER + 1)
@AutoConfigureAfter(CamundaSpringSecurityOAuth2CommonAutoConfiguration.class)
@ConditionalOnBean(CamundaBpmProperties.class)
@ConditionalOnClass(CamundaBpmRestInitializer.class)
@Configuration
public class CamundaSpringSecurityOAuth2EngineAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaSpringSecurityOAuth2EngineAutoConfiguration.class);

  @Bean
  public FilterRegistrationBean<?> engineRestAuthenticationFilter(JerseyApplicationPath applicationPath) {
    FilterRegistrationBean<Filter> filterRegistration = new FilterRegistrationBean<>();
    filterRegistration.setName("Container Based Authentication Filter for engine-rest");
    filterRegistration.setFilter(new ProcessEngineAuthenticationFilter());
    filterRegistration.setInitParameters(Map.of(
        ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM, CompositeOAuth2AuthenticationProvider.class.getName()));
    // make sure the filter is registered after the Spring Security Filter Chain
    filterRegistration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
    filterRegistration.addUrlPatterns(applicationPath.getPath() + "/*");
    filterRegistration.setDispatcherTypes(DispatcherType.REQUEST);
    return filterRegistration;
  }

  @Bean
  @Order(1) 
  public SecurityFilterChain engineRestSecurityFilterChain(HttpSecurity http, JerseyApplicationPath applicationPath) throws Exception {

    logger.info("Enabling Camunda Spring Security oauth2 integration for engine-rest");
    String engineRestPath = applicationPath.getPath();

    // @formatter:off
    http.securityMatcher(request -> {
          String fullPath = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
          return fullPath.startsWith(engineRestPath);
        })
        .authorizeHttpRequests(c -> c
            .requestMatchers(engineRestPath + "/**").permitAll())
        //.anonymous(AbstractHttpConfigurer::disable)
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    // @formatter:on
    return http.build();
  }
}
