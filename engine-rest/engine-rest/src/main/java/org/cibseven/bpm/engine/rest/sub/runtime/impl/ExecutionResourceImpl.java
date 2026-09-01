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
package org.cibseven.bpm.engine.rest.sub.runtime.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.rest.dto.CreateIncidentDto;
import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ExecutionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.ExecutionTriggerDto;
import org.cibseven.bpm.engine.rest.dto.runtime.AdHocActivityInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.TriggerAdHocActivitiesDto;
import org.cibseven.bpm.engine.rest.dto.runtime.IncidentDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.sub.VariableResource;
import org.cibseven.bpm.engine.rest.sub.runtime.EventSubscriptionResource;
import org.cibseven.bpm.engine.rest.sub.runtime.ExecutionResource;
import org.cibseven.bpm.engine.runtime.Execution;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.variable.VariableMap;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ExecutionResourceImpl implements ExecutionResource {

  protected ProcessEngine engine;
  protected String executionId;
  protected ObjectMapper objectMapper;

  public ExecutionResourceImpl(ProcessEngine engine, String executionId, ObjectMapper objectMapper) {
    this.engine = engine;
    this.executionId = executionId;
    this.objectMapper = objectMapper;
  }

  @Override
  public ExecutionDto getExecution() {
    RuntimeService runtimeService = engine.getRuntimeService();
    Execution execution = runtimeService.createExecutionQuery().executionId(executionId).singleResult();

    if (execution == null) {
      throw new InvalidRequestException(Status.NOT_FOUND, "Execution with id " + executionId + " does not exist");
    }

    return ExecutionDto.fromExecution(execution);
  }

  @Override
  public void signalExecution(ExecutionTriggerDto triggerDto) {
    RuntimeService runtimeService = engine.getRuntimeService();
    try {
      VariableMap variables = VariableValueDto.toMap(triggerDto.getVariables(), engine, objectMapper);
      runtimeService.signal(executionId, variables);

    } catch (RestException e) {
      String errorMessage = String.format("Cannot signal execution %s: %s", executionId, e.getMessage());
      throw new InvalidRequestException(e.getStatus(), e, errorMessage);

    } catch (AuthorizationException e) {
      throw e;

    } catch (ProcessEngineException e) {
      throw new RestException(Status.INTERNAL_SERVER_ERROR, e, "Cannot signal execution " + executionId + ": " + e.getMessage());

    }
  }

  @Override
  public VariableResource getLocalVariables() {
    return new LocalExecutionVariablesResource(engine, executionId, objectMapper);
  }

  @Override
  public EventSubscriptionResource getMessageEventSubscription(String messageName) {
    return new MessageEventSubscriptionResource(engine, executionId, messageName, objectMapper);
  }

  @Override
  public List<AdHocActivityInstanceDto> triggerAdHocActivities(TriggerAdHocActivitiesDto dto) {
    List<String> activityIds = dto == null ? null : dto.getActivityIds();

    Map<String, Map<String, Object>> activityVariables = null;
    if (dto != null && dto.getActivityVariables() != null) {
      activityVariables = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, VariableValueDto>> entry : dto.getActivityVariables().entrySet()) {
        activityVariables.put(entry.getKey(),
            VariableValueDto.toMap(entry.getValue(), engine, objectMapper));
      }
    }

    List<String> activityInstanceIds;
    try {
      activityInstanceIds = engine.getRuntimeService()
          .triggerAdHocActivities(executionId, activityIds, activityVariables);

      // BadUserRequestException, not ProcessEngineException. Everything this command refuses — an
      // activity that is not directly startable, an unknown id, an execution that is not an ad hoc
      // scope — is the caller's mistake and must be a 400. Letting it fall through to the generic
      // engine-exception handler would report every one of them as a 500.
    } catch (BadUserRequestException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }

    List<AdHocActivityInstanceDto> result = new ArrayList<>();
    Iterator<String> requested = activityIds.iterator();
    for (String activityInstanceId : activityInstanceIds) {
      result.add(new AdHocActivityInstanceDto(requested.next(), activityInstanceId));
    }
    return result;
  }

  @Override
  public void completeAdHocSubProcess(ExecutionTriggerDto dto) {
    VariableMap variables = dto == null ? null
        : VariableValueDto.toMap(dto.getVariables(), engine, objectMapper);

    try {
      engine.getRuntimeService().completeAdHocSubProcess(executionId, variables);

      // Caught here too, so the two endpoints behave the same way. The reference implementation
      // catches it in one and not the other, which is worse than not catching it in either.
    } catch (BadUserRequestException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  @Override
  public IncidentDto createIncident(CreateIncidentDto createIncidentDto) {
    Incident newIncident = null;

    try {
      newIncident = engine.getRuntimeService()
          .createIncident(createIncidentDto.getIncidentType(), executionId, createIncidentDto.getConfiguration(), createIncidentDto.getMessage());
    } catch (BadUserRequestException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
    return IncidentDto.fromIncident(newIncident);
  }
}
