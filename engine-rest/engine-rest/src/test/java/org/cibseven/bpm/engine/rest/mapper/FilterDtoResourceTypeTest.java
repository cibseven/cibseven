/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.rest.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.cibseven.bpm.engine.rest.dto.AbstractQueryDto;
import org.cibseven.bpm.engine.rest.dto.runtime.FilterDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskQueryDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code resourceType} is both a property of {@link FilterDto} and the external type id of its
 * {@code query}. Since Jackson 3.2 (jackson-databind #1329) an external type id is only passed on
 * to the property of the same name when the type info is {@code visible}; without it a filter
 * created through the REST API reaches the engine with resource type {@code null} (CIB7-2820).
 */
public class FilterDtoResourceTypeTest {

  static ObjectMapper mapper;

  @BeforeAll
  public static void setUpMapper() {
    mapper = new JacksonConfigurator().getContext(Object.class);
  }

  @Test
  public void shouldKeepTheTypeIdVisibleOnTheQuery() throws Exception {
    JsonTypeInfo typeInfo = FilterDto.class.getMethod("setQuery", AbstractQueryDto.class)
        .getAnnotation(JsonTypeInfo.class);

    assertThat(typeInfo.include()).isEqualTo(JsonTypeInfo.As.EXTERNAL_PROPERTY);
    assertThat(typeInfo.property()).isEqualTo("resourceType");
    assertThat(typeInfo.visible()).isTrue();
  }

  @Test
  public void shouldReadResourceTypeGivenBeforeTheQuery() throws Exception {
    FilterDto dto = mapper.readValue(
        "{\"resourceType\":\"Task\",\"name\":\"f\",\"query\":{\"assignee\":\"demo\"},\"properties\":{}}",
        FilterDto.class);

    assertThat(dto.getResourceType()).isEqualTo("Task");
    assertThat(dto.getQuery()).isInstanceOf(TaskQueryDto.class);
    assertThat(((TaskQueryDto) dto.getQuery()).getAssignee()).isEqualTo("demo");
  }

  @Test
  public void shouldReadResourceTypeGivenAfterTheQuery() throws Exception {
    FilterDto dto = mapper.readValue(
        "{\"name\":\"f\",\"query\":{\"assignee\":\"demo\"},\"resourceType\":\"Task\"}",
        FilterDto.class);

    assertThat(dto.getResourceType()).isEqualTo("Task");
    assertThat(dto.getQuery()).isInstanceOf(TaskQueryDto.class);
  }

  @Test
  public void shouldLeaveResourceTypeEmptyWhenNotGiven() throws Exception {
    FilterDto dto = mapper.readValue("{\"name\":\"f\",\"query\":{\"assignee\":\"demo\"}}", FilterDto.class);

    assertThat(dto.getResourceType()).isNull();
    assertThat(dto.getQuery()).isInstanceOf(TaskQueryDto.class);
  }

  @Test
  public void shouldWriteResourceTypeOnce() throws Exception {
    FilterDto dto = new FilterDto();
    dto.setResourceType("Task");
    dto.setName("f");
    dto.setQuery(new TaskQueryDto());

    String json = mapper.writeValueAsString(dto);

    assertThat(json.split("\"resourceType\"", -1)).hasSize(2);
    assertThat(json).contains("\"resourceType\":\"Task\"");
  }
}
