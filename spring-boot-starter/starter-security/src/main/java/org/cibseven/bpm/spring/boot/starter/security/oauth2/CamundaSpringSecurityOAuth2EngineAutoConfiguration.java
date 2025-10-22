package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.ResourceServerConfiguredCondition;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.rest.CamundaBpmRestInitializer;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.OAuth2AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.JerseyApplicationPath;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.annotation.Nullable;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

@AutoConfigureOrder(CamundaSpringSecurityOAuth2CommonAutoConfiguration.CAMUNDA_OAUTH2_ORDER + 1)
@AutoConfigureAfter(CamundaSpringSecurityOAuth2CommonAutoConfiguration.class)
@ConditionalOnBean(CamundaBpmProperties.class)
@ConditionalOnClass(CamundaBpmRestInitializer.class)
@Conditional(ResourceServerConfiguredCondition.class)
@Configuration
public class CamundaSpringSecurityOAuth2EngineAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaSpringSecurityOAuth2EngineAutoConfiguration.class);
  private final OAuth2Properties oAuth2Properties;
  private final OAuth2ResourceServerProperties oAuth2ResourceServerProperties;

  public CamundaSpringSecurityOAuth2EngineAutoConfiguration(OAuth2Properties oAuth2Properties,
                                                            OAuth2ResourceServerProperties oAuth2ResourceServerProperties) {
   this.oAuth2Properties = oAuth2Properties;
   this.oAuth2ResourceServerProperties = oAuth2ResourceServerProperties;
  }
  
  @Bean
  public FilterRegistrationBean<?> engineRestAuthenticationFilter(JerseyApplicationPath applicationPath) {
    FilterRegistrationBean<Filter> filterRegistration = new FilterRegistrationBean<>();
    filterRegistration.setName("Container Based Authentication Filter for engine-rest");
    filterRegistration.setFilter(new ProcessEngineAuthenticationFilter());
    filterRegistration.setInitParameters(Map.of(
        ProcessEngineAuthenticationFilter.AUTHENTICATION_PROVIDER_PARAM, OAuth2AuthenticationProvider.class.getName()));
    // make sure the filter is registered after the Spring Security Filter Chain
    filterRegistration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
    filterRegistration.addUrlPatterns(applicationPath.getPath() + "/*");
    filterRegistration.setDispatcherTypes(DispatcherType.REQUEST);
    return filterRegistration;
  }

  @Bean
  @ConditionalOnProperty(name = "identity-provider.group-name-attribute", prefix = OAuth2Properties.PREFIX)
  protected JwtAuthenticationConverter oauth2JwtAuthenticationConverter() {
      JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter((Converter<Jwt, Collection<GrantedAuthority>>) jwt -> {
          var identityProviderProperties = oAuth2Properties.getIdentityProvider();
          var groupNameAttribute = identityProviderProperties.getGroupNameAttribute();

          List<String> groups = null;
          try {
            groups = jwt.getClaimAsStringList(groupNameAttribute);
          } catch (IllegalArgumentException e) {
            logger.debug("Claim {} is not a list of strings, trying to parse as single string", groupNameAttribute);
            String groupNameDelimiter = identityProviderProperties.getGroupNameDelimiter();
            String groupsAttribute = jwt.getClaimAsString(groupNameAttribute);
            if (groupsAttribute != null) {
              groups = List.of(groupsAttribute.split(groupNameDelimiter));
            }
          }
          if (groups == null) {
              logger.debug("Claim {} is not available", groupNameAttribute);
              return List.of();
          }
          return groups
                 .stream()
                 .map(SimpleGrantedAuthority::new)
                 .collect(Collectors.toList());
      });
      
      String principalClaimName = Optional.ofNullable(oAuth2ResourceServerProperties.getJwt())
                .map(OAuth2ResourceServerProperties.Jwt::getPrincipalClaimName)
                .filter(s -> !s.isBlank())
                .orElse("preferred_username");
      converter.setPrincipalClaimName(principalClaimName);
      
      return converter;
  }

  @Bean
  @Order(1) 
  public SecurityFilterChain engineRestSecurityFilterChain(HttpSecurity http, 
          JerseyApplicationPath applicationPath,
          @Nullable JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

    logger.info("Enabling Camunda Spring Security oauth2 integration for engine-rest");
    String engineRestPath = applicationPath.getPath();

    // @formatter:off
    http.securityMatcher(request -> {
          String fullPath = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
          return fullPath.startsWith(engineRestPath);
        })
        .authorizeHttpRequests(c -> c
          .requestMatchers(engineRestPath + "/**").authenticated())
        .anonymous(AbstractHttpConfigurer::disable)
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
             if (jwtAuthenticationConverter != null) {
               jwt.jwtAuthenticationConverter(jwtAuthenticationConverter);
             }
        }));
    // @formatter:on
    return http.build();
  }
}
