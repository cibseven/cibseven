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
package org.cibseven.bpm.spring.boot.starter.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.history.HistoryLevel;
import org.cibseven.bpm.engine.impl.history.HistoryLevelAudit;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventType;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
public class HistoryLevelDeterminatorJdbcTemplateImplTest {

  @Mock
  private JdbcTemplate jdbcTemplate;

  private CamundaBpmProperties camundaBpmProperties;

  @BeforeEach
  public void before() {
    camundaBpmProperties = new CamundaBpmProperties();
  }

  @Test
  public void afterPropertiesSetTest1() throws Exception {
    camundaBpmProperties = new CamundaBpmProperties();
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    determinator.setJdbcTemplate(jdbcTemplate);
    determinator.setCamundaBpmProperties(camundaBpmProperties);
    determinator.afterPropertiesSet();
    assertEquals(ProcessEngineConfiguration.HISTORY_FULL, determinator.defaultHistoryLevel);
  }

  @Test
  public void afterPropertiesSetTest2() throws Exception {
    camundaBpmProperties = new CamundaBpmProperties();
    final String historyLevelDefault = "defaultValue";
    camundaBpmProperties.setHistoryLevelDefault(historyLevelDefault);
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    determinator.setJdbcTemplate(jdbcTemplate);
    determinator.setCamundaBpmProperties(camundaBpmProperties);
    determinator.afterPropertiesSet();
    assertEquals(historyLevelDefault, determinator.defaultHistoryLevel);
  }

  @Test
  public void afterPropertiesSetTest3() throws Exception {
    assertThatThrownBy(() -> {
      new HistoryLevelDeterminatorJdbcTemplateImpl().afterPropertiesSet();
    }).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void afterPropertiesSetTest4() throws Exception {
    assertThatThrownBy(() -> {
      HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
      determinator.setJdbcTemplate(jdbcTemplate);
      determinator.afterPropertiesSet();
    }).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void afterPropertiesSetTest5() throws Exception {
    assertThatThrownBy(() -> {
      HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
      determinator.setCamundaBpmProperties(camundaBpmProperties);
      determinator.afterPropertiesSet();
    }).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void determinedTest() throws Exception {
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    final String defaultHistoryLevel = "test";
    determinator.setDefaultHistoryLevel(defaultHistoryLevel);
    determinator.setJdbcTemplate(jdbcTemplate);
    determinator.setCamundaBpmProperties(camundaBpmProperties);
    determinator.afterPropertiesSet();
    HistoryLevel historyLevel = new HistoryLevelAudit();
    when(jdbcTemplate.queryForObject(determinator.getSql(), Integer.class)).thenReturn(historyLevel.getId());
    String determineHistoryLevel = determinator.determineHistoryLevel();
    assertEquals(historyLevel.getName(), determineHistoryLevel);
  }

  @Test
  public void determinedExceptionIgnoringTest() throws Exception {
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    final String defaultHistoryLevel = "test";
    determinator.setDefaultHistoryLevel(defaultHistoryLevel);
    determinator.setJdbcTemplate(jdbcTemplate);
    determinator.setCamundaBpmProperties(camundaBpmProperties);
    determinator.afterPropertiesSet();
    when(jdbcTemplate.queryForObject(determinator.getSql(), Integer.class)).thenThrow(new DataRetrievalFailureException(""));
    String determineHistoryLevel = determinator.determineHistoryLevel();
    assertEquals(determinator.defaultHistoryLevel, determineHistoryLevel);
    verify(jdbcTemplate).queryForObject(determinator.getSql(), Integer.class);
  }

  @Test
  public void determinedExceptionNotIgnoringTest() throws Exception {
    assertThatThrownBy(() -> {
      HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
      determinator.setIgnoreDataAccessException(false);
      final String defaultHistoryLevel = "test";
      determinator.setDefaultHistoryLevel(defaultHistoryLevel);
      determinator.setJdbcTemplate(jdbcTemplate);
      determinator.setCamundaBpmProperties(camundaBpmProperties);
      determinator.afterPropertiesSet();
      when(jdbcTemplate.queryForObject(determinator.getSql(), Integer.class)).thenThrow(new DataRetrievalFailureException(""));
      determinator.determineHistoryLevel();
    }).isInstanceOf(DataRetrievalFailureException.class);
  }

  @Test
  public void getSqlTest() {
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    determinator.setCamundaBpmProperties(camundaBpmProperties);
    assertEquals("SELECT VALUE_ FROM ACT_GE_PROPERTY WHERE NAME_='historyLevel'", determinator.getSql());
    camundaBpmProperties.getDatabase().setTablePrefix("TEST_");
    assertEquals("SELECT VALUE_ FROM TEST_ACT_GE_PROPERTY WHERE NAME_='historyLevel'", determinator.getSql());
  }

  @Test
  public void getHistoryLevelFromTest() {
    HistoryLevelDeterminatorJdbcTemplateImpl determinator = new HistoryLevelDeterminatorJdbcTemplateImpl();
    assertEquals(determinator.getDefaultHistoryLevel(), determinator.getHistoryLevelFrom(-1));
    assertFalse(determinator.historyLevels.isEmpty());
    HistoryLevel customHistoryLevel = new HistoryLevel() {

      @Override
      public boolean isHistoryEventProduced(HistoryEventType eventType, Object entity) {
        return false;
      }

      @Override
      public String getName() {
        return "custom";
      }

      @Override
      public int getId() {
        return Integer.MAX_VALUE;
      }
    };

    determinator.addCustomHistoryLevels(Collections.singleton(customHistoryLevel));
    assertTrue(determinator.historyLevels.contains(customHistoryLevel));

    for (HistoryLevel historyLevel : determinator.historyLevels) {
      assertEquals(historyLevel.getName(), determinator.getHistoryLevelFrom(historyLevel.getId()));
    }
  }

}
