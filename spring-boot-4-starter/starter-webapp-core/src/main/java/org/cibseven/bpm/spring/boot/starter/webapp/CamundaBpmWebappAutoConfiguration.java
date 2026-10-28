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
package org.cibseven.bpm.spring.boot.starter.webapp;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.SessionTrackingMode;

import org.cibseven.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.property.WebappProperty;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.AppendTrailingSlashFilter;
import org.cibseven.webapp.rest.BaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Import({
  org.cibseven.webapp.SevenWebclientContext.class
})
@Configuration
@ConditionalOnProperty(prefix = WebappProperty.PREFIX, name = "enabled", matchIfMissing = true)
@ConditionalOnBean(CamundaBpmProperties.class)
@ConditionalOnWebApplication
@AutoConfigureAfter(CamundaBpmAutoConfiguration.class)
public class CamundaBpmWebappAutoConfiguration implements WebMvcConfigurer, WebMvcRegistrations {

  @Autowired
  private CamundaBpmProperties properties;

  @Bean
  public ServletContextInitializer webappSessionTrackingInitializer() {
    return servletContext -> servletContext.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.COOKIE));
  }

  // redirects the application path to the index, see addViewControllers
  @Bean
  public FilterRegistrationBean<AppendTrailingSlashFilter> webappAppendTrailingSlashFilter() {
    FilterRegistrationBean<AppendTrailingSlashFilter> registration = new FilterRegistrationBean<>(new AppendTrailingSlashFilter());
    registration.setName("Webapp AppendTrailingSlashFilter");
    registration.addUrlPatterns(properties.getWebapp().getApplicationPath());
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  @Override
  public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
    RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
    mapping.setPathPrefixes(Map.of(properties.getWebapp().getApplicationPath(),
      HandlerTypePredicate.forAssignableType(BaseService.class)));
    return mapping;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    WebappProperty webapp = properties.getWebapp();
    registry.addResourceHandler(webapp.getApplicationPath() + "/**").addResourceLocations("classpath:" + webapp.getWebjarClasspath()+ "/");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {

    WebappProperty webapp = properties.getWebapp();
    // only redirect from index ("/") if index redirect is enabled
    if (webapp.isIndexRedirectEnabled()) {
      registry.addRedirectViewController("/", webapp.getApplicationPath() + "/");
    }
    // always serve the webapp index; AppendTrailingSlashFilter redirects the application path to it
    registry.addViewController(webapp.getApplicationPath() + "/").setViewName("forward:" + webapp.getApplicationPath() + "/index.html");
  }

}
