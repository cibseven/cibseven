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

import java.util.Map;

import org.cibseven.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.property.WebappProperty;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.LazyDelegateFilter.InitHook;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.LazyInitRegistration;
import org.cibseven.bpm.spring.boot.starter.webapp.filter.ResourceLoaderDependingFilter;
import org.cibseven.webapp.rest.BaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
@ConditionalOnProperty(prefix = WebappProperty.PREFIX, name = "enabled", matchIfMissing = true)
@ConditionalOnBean(CamundaBpmProperties.class)
@ConditionalOnWebApplication
@AutoConfigureAfter(CamundaBpmAutoConfiguration.class)
public class CamundaBpmWebappAutoConfiguration implements WebMvcConfigurer, WebMvcRegistrations {

  @Autowired
  private ResourceLoader resourceLoader;

  @Autowired
  private CamundaBpmProperties properties;

  @Bean
  public CamundaBpmWebappInitializer camundaBpmWebappInitializer() {
    return new CamundaBpmWebappInitializer(properties);
  }

  @Bean(name = "resourceLoaderDependingInitHook")
  public InitHook<ResourceLoaderDependingFilter> resourceLoaderDependingInitHook() {
    return filter -> {
      filter.setResourceLoader(resourceLoader);
      filter.setWebappProperty(properties.getWebapp());
    };
  }

  @Bean
  public LazyInitRegistration lazyInitRegistration() {
    return new LazyInitRegistration();
  }

  @Bean
  public FaviconResourceResolver faviconResourceResolver() {
    return new FaviconResourceResolver();
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
    final String legacyClasspath = "classpath:" + properties.getWebapp().getLegacyWebjarClasspath();
    WebappProperty webapp = properties.getWebapp();
    String legacyApplicationPath = webapp.getLegacyApplicationPath();

    registry.addResourceHandler(legacyApplicationPath + "/lib/**")
        .addResourceLocations(legacyClasspath + "/lib/");
    registry.addResourceHandler(legacyApplicationPath + "/api/**")
        .addResourceLocations("classpath:/api/");
    registry.addResourceHandler(legacyApplicationPath + "/app/**")
        .addResourceLocations(legacyClasspath + "/app/");
    registry.addResourceHandler(legacyApplicationPath + "/assets/**")
        .addResourceLocations(legacyClasspath + "/assets/");
    registry.addResourceHandler(legacyApplicationPath + "/favicon.ico")
        .addResourceLocations(legacyClasspath + "/") // add slash to get rid of the WARN log
        .resourceChain(true)
        .addResolver(faviconResourceResolver());    
      
     registry.addResourceHandler(webapp.getApplicationPath() + "/**").addResourceLocations("classpath:" + webapp.getWebjarClasspath()+ "/");     

  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {

    WebappProperty webapp = properties.getWebapp();
    if (webapp.isIndexRedirectEnabled()) {
		// using AppendTrailingSlashFilter
      registry.addRedirectViewController("/", webapp.getApplicationPath() + "/");
	    registry.addViewController(webapp.getApplicationPath() + "/").setViewName("forward:" + webapp.getApplicationPath() + "/index.html");
    }
  }

}
