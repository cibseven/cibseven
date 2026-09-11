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
package org.cibseven.bpm.engine.rest.dto.runtime;

import java.util.Map;

import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.filter.Filter;
import org.cibseven.bpm.engine.rest.dto.AbstractQueryDto;
import org.cibseven.bpm.engine.rest.dto.task.TaskQueryDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

public class FilterDto {

  protected String id;
  protected String resourceType;
  protected String name;
  protected String owner;
  // A filter's query is always a task query: fromFilter() only ever builds a TaskQueryDto,
  // and FilterResourceImpl#getQueryDtoForQuery rejects any non-TASK resource type. Declaring
  // the concrete TaskQueryDto (rather than the abstract AbstractQueryDto) lets Jackson
  // instantiate the query on deserialization; the abstract type has no usable creator and
  // @JsonDeserialize(as = ...) is not honored for it by the Jackson version shipped with
  // WildFly 40 (2.21.x via RESTEasy), which previously caused an HTTP 500 on POST /filter/create.
  protected TaskQueryDto query;
  protected Map<String, Object> properties;

  protected Long itemCount;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public AbstractQueryDto<?> getQuery() {
    return query;
  }

  // A filter's query is always a Task query: FilterDto.fromFilter only creates a
  // TaskQueryDto and FilterResourceImpl rejects every non-Task resource type.
  public void setQuery(TaskQueryDto query) {
    this.query = query;
  }

  public Map<String, Object> getProperties() {
    return properties;
  }

  public void setProperties(Map<String, Object> properties) {
    this.properties = properties;
  }

  @JsonInclude(Include.NON_NULL)
  public Long getItemCount() {
    return itemCount;
  }

  public void setItemCount(Long itemCount) {
    this.itemCount = itemCount;
  }

  public static FilterDto fromFilter(Filter filter) {
    FilterDto dto = new FilterDto();
    dto.id = filter.getId();
    dto.resourceType = filter.getResourceType();
    dto.name = filter.getName();
    dto.owner = filter.getOwner();

    if (EntityTypes.TASK.equals(filter.getResourceType())) {
      dto.query = TaskQueryDto.fromQuery(filter.getQuery());
    }

    dto.properties = filter.getProperties();
    return dto;
  }

  public void updateFilter(Filter filter, ProcessEngine engine) {
    if (getResourceType() != null && !getResourceType().equals(filter.getResourceType())) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "Unable to update filter from resource type '" + filter.getResourceType() + "' to '" + getResourceType() + "'");
    }
    filter.setName(getName());
    filter.setOwner(getOwner());
    filter.setQuery(query.toQuery(engine));
    filter.setProperties(getProperties());
  }

}
