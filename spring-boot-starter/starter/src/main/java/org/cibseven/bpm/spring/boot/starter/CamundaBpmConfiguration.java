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
package org.cibseven.bpm.spring.boot.starter;

import static org.cibseven.bpm.spring.boot.starter.jdbc.HistoryLevelDeterminatorJdbcTemplateImpl.createHistoryLevelDeterminator;

import java.util.List;

import org.cibseven.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaAuthorizationConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaDatasourceConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaDeploymentConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaFailedJobConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaHistoryConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaHistoryLevelAutoHandlingConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaJobConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaMetricsConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.CamundaProcessEngineConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.condition.NeedsHistoryAutoConfigurationCondition;
import org.cibseven.bpm.spring.boot.starter.configuration.id.IdGeneratorConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.custom.CreateAdminUserConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.custom.CreateFilterConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultAuthorizationConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultDatasourceConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultDeploymentConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultFailedJobConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultHistoryConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultHistoryLevelAutoHandlingConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultJobConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultJobConfiguration.JobConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultMetricsConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.DefaultProcessEngineConfiguration;
import org.cibseven.bpm.spring.boot.starter.configuration.impl.GenericPropertiesConfiguration;
import org.cibseven.bpm.spring.boot.starter.event.EventPublisherPlugin;
import org.cibseven.bpm.spring.boot.starter.jdbc.HistoryLevelDeterminator;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.spring.boot.starter.telemetry.CamundaIntegrationDeterminator;
import org.cibseven.bpm.spring.boot.starter.util.CamundaSpringBootUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({
    JobConfiguration.class,
    IdGeneratorConfiguration.class
})
public class CamundaBpmConfiguration {

  @Bean
  @ConditionalOnMissingBean(ProcessEngineConfigurationImpl.class)
  public ProcessEngineConfigurationImpl processEngineConfigurationImpl(List<ProcessEnginePlugin> processEnginePlugins) {
    final SpringProcessEngineConfiguration configuration = CamundaSpringBootUtil.springProcessEngineConfiguration();
    configuration.getProcessEnginePlugins().add(new CompositeProcessEnginePlugin(processEnginePlugins));
    return configuration;
  }

  @Bean
  @ConditionalOnMissingBean(DefaultProcessEngineConfiguration.class)
  public static CamundaProcessEngineConfiguration camundaProcessEngineConfiguration() {
    return new DefaultProcessEngineConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaDatasourceConfiguration.class)
  public static CamundaDatasourceConfiguration camundaDatasourceConfiguration() {
    return new DefaultDatasourceConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaJobConfiguration.class)
  @ConditionalOnProperty(prefix = "camunda.bpm.job-execution", name = "enabled", havingValue = "true", matchIfMissing = true)
  public static CamundaJobConfiguration camundaJobConfiguration() {
    return new DefaultJobConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaHistoryConfiguration.class)
  public static CamundaHistoryConfiguration camundaHistoryConfiguration() {
    return new DefaultHistoryConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaMetricsConfiguration.class)
  public static CamundaMetricsConfiguration camundaMetricsConfiguration() {
    return new DefaultMetricsConfiguration();
  }

  //TODO to be removed within CAM-8108
  @Bean(name = "historyLevelAutoConfiguration")
  @ConditionalOnMissingBean(CamundaHistoryLevelAutoHandlingConfiguration.class)
  @ConditionalOnProperty(prefix = "camunda.bpm", name = "history-level", havingValue = "auto", matchIfMissing = false)
  @Conditional(NeedsHistoryAutoConfigurationCondition.class)
  public static CamundaHistoryLevelAutoHandlingConfiguration historyLevelAutoHandlingConfiguration() {
    return new DefaultHistoryLevelAutoHandlingConfiguration();
  }

  //TODO to be removed within CAM-8108
  @Bean(name = "historyLevelDeterminator")
  @ConditionalOnMissingBean(name = { "camundaBpmJdbcTemplate", "historyLevelDeterminator" })
  @ConditionalOnBean(name = "historyLevelAutoConfiguration")
  public static HistoryLevelDeterminator historyLevelDeterminator(CamundaBpmProperties camundaBpmProperties, JdbcTemplate jdbcTemplate) {
    return createHistoryLevelDeterminator(camundaBpmProperties, jdbcTemplate);
  }

  //TODO to be removed within CAM-8108
  @Bean(name = "historyLevelDeterminator")
  @ConditionalOnBean(name = { "camundaBpmJdbcTemplate", "historyLevelAutoConfiguration", "historyLevelDeterminator" })
  @ConditionalOnMissingBean(name = "historyLevelDeterminator")
  public static HistoryLevelDeterminator historyLevelDeterminatorMultiDatabase(CamundaBpmProperties camundaBpmProperties,
      @Qualifier("camundaBpmJdbcTemplate") JdbcTemplate jdbcTemplate) {
    return createHistoryLevelDeterminator(camundaBpmProperties, jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(CamundaAuthorizationConfiguration.class)
  public static CamundaAuthorizationConfiguration camundaAuthorizationConfiguration() {
    return new DefaultAuthorizationConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaDeploymentConfiguration.class)
  public static CamundaDeploymentConfiguration camundaDeploymentConfiguration() {
    return new DefaultDeploymentConfiguration();
  }

  @Bean
  public GenericPropertiesConfiguration genericPropertiesConfiguration() {
    return new GenericPropertiesConfiguration();
  }

  @Bean
  @ConditionalOnProperty(prefix = "camunda.bpm.admin-user", name = "id")
  public CreateAdminUserConfiguration createAdminUserConfiguration() {
    return new CreateAdminUserConfiguration();
  }

  @Bean
  @ConditionalOnMissingBean(CamundaFailedJobConfiguration.class)
  public static CamundaFailedJobConfiguration failedJobConfiguration() {
    return new DefaultFailedJobConfiguration();
  }

  @Bean
  @ConditionalOnProperty(prefix = "camunda.bpm.filter", name = "create")
  public CreateFilterConfiguration createFilterConfiguration() {
    return new CreateFilterConfiguration();
  }

  @Bean
  public EventPublisherPlugin eventPublisherPlugin(CamundaBpmProperties properties, ApplicationEventPublisher publisher) {
    return new EventPublisherPlugin(properties.getEventing(), publisher);
  }

  @Bean
  public CamundaIntegrationDeterminator camundaIntegrationDeterminator() {
    return new CamundaIntegrationDeterminator();
  }
}
