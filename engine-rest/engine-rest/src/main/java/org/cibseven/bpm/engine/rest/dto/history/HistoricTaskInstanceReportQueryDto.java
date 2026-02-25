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
package org.cibseven.bpm.engine.rest.dto.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.history.HistoricTaskInstanceReport;
import org.cibseven.bpm.engine.history.HistoricTaskInstanceReportResult;
import org.cibseven.bpm.engine.rest.dto.AbstractReportDto;
import org.cibseven.bpm.engine.rest.dto.CamundaQueryParam;
import org.cibseven.bpm.engine.rest.dto.converter.DateConverter;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Date;
import java.util.List;

/**
 * @author Stefan Hentschel.
 */
public class HistoricTaskInstanceReportQueryDto extends AbstractReportDto<HistoricTaskInstanceReport> {

  public static final String PROCESS_DEFINITION = "processDefinition";
  public static final String TASK_NAME = "taskName";

  protected Date completedBefore;
  protected Date completedAfter;
  protected String groupby;


  public HistoricTaskInstanceReportQueryDto() {}

  public HistoricTaskInstanceReportQueryDto(ObjectMapper objectMapper, MultivaluedMap<String, String> queryParameters) {
    super(objectMapper, queryParameters);
  }

  public Date getCompletedBefore() {
    return completedBefore;
  }

  public Date getCompletedAfter() {
    return completedAfter;
  }

  public String getGroupBy() {
    return groupby;
  }

  @CamundaQueryParam(value = "completedAfter", converter = DateConverter.class)
  public void setCompletedAfter(Date completedAfter) {
    this.completedAfter = completedAfter;
  }

  @CamundaQueryParam(value = "completedBefore", converter = DateConverter.class)
  public void setCompletedBefore(Date completedBefore) {
    this.completedBefore = completedBefore;
  }

  @CamundaQueryParam("groupBy")
  public void setGroupBy(String groupby) {
    this.groupby = groupby;
  }

  protected void applyFilters(HistoricTaskInstanceReport reportQuery) {
    if (completedBefore != null) {
      reportQuery.completedBefore(completedBefore);
    }
    if (completedAfter != null) {
      reportQuery.completedAfter(completedAfter);
    }

    if(REPORT_TYPE_DURATION.equals(reportType)) {
      if(periodUnit == null) {
        throw new InvalidRequestException(Response.Status.BAD_REQUEST, "periodUnit is null");
      }
    }

  }

  protected HistoricTaskInstanceReport createNewReportQuery(ProcessEngine engine) {
    return engine.getHistoryService().createHistoricTaskInstanceReport();
  }

  public List<HistoricTaskInstanceReportResult> executeCompletedReport(ProcessEngine engine) {
    HistoricTaskInstanceReport reportQuery = createNewReportQuery(engine);
    applyFilters(reportQuery);

    if(PROCESS_DEFINITION.equals(groupby)) {
      return reportQuery.countByProcessDefinitionKey();
    } else if( TASK_NAME.equals(groupby) ){
      return reportQuery.countByTaskName();
    } else {
      throw new InvalidRequestException(Response.Status.BAD_REQUEST, "groupBy parameter has invalid value: " + groupby);
    }
  }
}
