package org.cibseven.bpm.spring.boot.starter.security.oauth2;

import org.cibseven.bpm.engine.spring.SpringProcessEngineServicesConfiguration;
import org.cibseven.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.OAuth2GrantedAuthoritiesMapper;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.OAuth2IdentityProviderPlugin;
import org.cibseven.bpm.spring.boot.starter.security.oauth2.impl.SsoLogoutSuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@AutoConfigureOrder(CamundaSpringSecurityOAuth2CommonAutoConfiguration.CAMUNDA_OAUTH2_ORDER)
@AutoConfigureAfter({CamundaBpmAutoConfiguration.class, SpringProcessEngineServicesConfiguration.class})
@ConditionalOnBean(CamundaBpmProperties.class)
@EnableConfigurationProperties(OAuth2Properties.class)
@Configuration
public class CamundaSpringSecurityOAuth2CommonAutoConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(CamundaSpringSecurityOAuth2CommonAutoConfiguration.class);
  public static final int CAMUNDA_OAUTH2_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;
  private final OAuth2Properties oAuth2Properties;

  public CamundaSpringSecurityOAuth2CommonAutoConfiguration(CamundaBpmProperties properties,
              OAuth2Properties oAuth2Properties) {
    this.oAuth2Properties = oAuth2Properties;
  }

  @Bean
  @ConditionalOnProperty(name = "identity-provider.enabled", havingValue = "true", prefix = OAuth2Properties.PREFIX, matchIfMissing = true)
  public OAuth2IdentityProviderPlugin identityProviderPlugin() {
    logger.debug("Registering OAuth2IdentityProviderPlugin");
    return new OAuth2IdentityProviderPlugin();
  }

  @Bean
  @ConditionalOnProperty(name = "identity-provider.group-name-attribute", prefix = OAuth2Properties.PREFIX)
  protected GrantedAuthoritiesMapper grantedAuthoritiesMapper() {
    logger.debug("Registering OAuth2GrantedAuthoritiesMapper");
    return new OAuth2GrantedAuthoritiesMapper(oAuth2Properties);
  }
}
