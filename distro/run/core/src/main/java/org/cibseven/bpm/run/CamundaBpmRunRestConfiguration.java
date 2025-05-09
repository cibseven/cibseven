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
package org.cibseven.bpm.run;

import jakarta.servlet.Filter;
import org.apache.catalina.filters.CorsFilter;
import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.engine.rest.security.auth.impl.CompositeAuthenticationProvider;
import org.cibseven.bpm.run.property.CamundaBpmRunAuthenticationProperties;
import org.cibseven.bpm.run.property.CamundaBpmRunCorsProperty;
import org.cibseven.bpm.run.property.CamundaBpmRunProperties;
import org.cibseven.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaBpmRestInitializer;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaJerseyResourceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.servlet.JerseyApplicationPath;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collections;

@EnableConfigurationProperties(CamundaBpmRunProperties.class)
@Configuration
@AutoConfigureAfter({ CamundaBpmAutoConfiguration.class })
@ConditionalOnClass(CamundaBpmRestInitializer.class)
public class CamundaBpmRunRestConfiguration {

  @Autowired
  CamundaBpmRunProperties camundaBpmRunProperties;

  /*
   * The CORS and Authentication filters need to run before other camunda
   * filters because they potentially block the request and this should be done
   * as early as possible.
   * 
   * The default order parameter for spring-boot managed filters is
   * org.springframework.core.Ordered.LOWEST_PRECEDENCE = Integer.MAX_VALUE.
   * Order can range from -Integer.MAX_VALUE to Integer.MAX_VALUE.
   * 
   * The CORS filter must run before the Authentication filter because CORS
   * preflight requests must not contain authentication. The CORS filter will
   * not invoke the next filter in the chain for preflight requests.
   */
  private static int CORS_FILTER_PRECEDENCE = 0;
  private static int AUTH_FILTER_PRECEDENCE = 1;

  @Bean
  @ConditionalOnProperty(name = "enabled", havingValue = "true", matchIfMissing = true, prefix = CamundaBpmRunAuthenticationProperties.PREFIX)
  public FilterRegistrationBean<Filter> processEngineAuthenticationFilter(JerseyApplicationPath applicationPath) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setName("cibseven-auth");
    registration.setFilter(new ProcessEngineAuthenticationFilter());
    registration.setOrder(AUTH_FILTER_PRECEDENCE);

    String restApiPathPattern = applicationPath.getPath();

	// Apply to all URLs under engine-rest except /engine-rest/identity/verify
	String[] urlPatterns = Arrays.asList(
        "/process-definition/*",
        "/process-instance/*",
        "/history/*",
        "/execution/*",
        "/batch/*",
        "/decision-definition/*",
        "/deployment/*",
        "/filter/*",
        "/incident/*",
        "/job-definition/*",
        "/job/*",
        "/telemetry/*",
        "/metrics/*",
        "/authorization/*",
        "/group/*",
        "/user/*",
        "/message/*",
        "/event-subscription/*",
        "/variable-instance/*",
        "/task/*",
        "/engine/*",
        "/identity/groups"
	).stream().map(pattern -> addUrl(restApiPathPattern, pattern)).toArray(String[]::new);
	registration.setAsyncSupported(true);

    // if nothing is set, use Http Basic authentication
    CamundaBpmRunAuthenticationProperties properties = camundaBpmRunProperties.getAuth();
    if (properties.getAuthentication() == null || CamundaBpmRunAuthenticationProperties.DEFAULT_AUTH.equals(properties.getAuthentication())) {
    	urlPatterns = new String[] { addUrl(restApiPathPattern, "/filter/*") };
    	registration.addInitParameter("authentication-provider", "org.cibseven.bpm.engine.rest.security.auth.impl.PseudoAuthenticationProvider");
    } else if (CamundaBpmRunAuthenticationProperties.COMPOSITE_AUTH.equals(properties.getAuthentication())) {
    	registration.addInitParameter("authentication-provider", "org.cibseven.bpm.engine.rest.security.auth.impl.CompositeAuthenticationProvider");
    } else if (CamundaBpmRunAuthenticationProperties.BASIC_AUTH.equals(properties.getAuthentication())) {
    	registration.addInitParameter("authentication-provider", "org.cibseven.bpm.engine.rest.security.auth.impl.HttpBasicAuthenticationProvider");
	}
    
    registration.addUrlPatterns(urlPatterns);
    return registration;
  }

  @Bean
  @ConditionalOnProperty(name = "enabled", havingValue = "true", prefix = CamundaBpmRunCorsProperty.PREFIX)
  public FilterRegistrationBean<Filter> corsFilter(JerseyApplicationPath applicationPath) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setName("camunda-cors");
    CorsFilter corsFilter = new CorsFilter();
    registration.setFilter(corsFilter);
    registration.setOrder(CORS_FILTER_PRECEDENCE);

    String restApiPathPattern = applicationPath.getUrlMapping();
    registration.addUrlPatterns(restApiPathPattern);

    registration.addInitParameter(CorsFilter.PARAM_CORS_ALLOWED_ORIGINS,
                                  camundaBpmRunProperties.getCors().getAllowedOrigins());
    registration.addInitParameter(CorsFilter.PARAM_CORS_ALLOWED_METHODS,
                                  CamundaBpmRunCorsProperty.DEFAULT_HTTP_METHODS);
    registration.addInitParameter(CorsFilter.PARAM_CORS_ALLOWED_HEADERS,
                                  camundaBpmRunProperties.getCors().getAllowedHeaders());
    registration.addInitParameter(CorsFilter.PARAM_CORS_EXPOSED_HEADERS,
                                  camundaBpmRunProperties.getCors().getExposedHeaders());
    registration.addInitParameter(CorsFilter.PARAM_CORS_SUPPORT_CREDENTIALS,
                                  String.valueOf(camundaBpmRunProperties.getCors().getAllowCredentials()));
    registration.addInitParameter(CorsFilter.PARAM_CORS_PREFLIGHT_MAXAGE,
                                  camundaBpmRunProperties.getCors().getPreflightMaxAge());

    return registration;
  }

  @Bean
  public CamundaJerseyResourceConfig camundaRunJerseyResourceConfig() {
    CamundaJerseyResourceConfig camundaJerseyResourceConfig = new CamundaJerseyResourceConfig();
    camundaJerseyResourceConfig.setProperties(Collections.singletonMap("jersey.config.server.wadl.disableWadl", camundaBpmRunProperties.getRest().isDisableWadl()));
    return camundaJerseyResourceConfig;
  }
  
  private String addUrl(String base, String extend) {
	  return (base + extend).replaceFirst("^(\\/+|([^/]))", "/$2");
  }

}
