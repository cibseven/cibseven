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
package org.cibseven.bpm.engine.impl.persistence.entity;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.db.ListQueryParameterObject;
import org.cibseven.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.cibseven.bpm.engine.impl.history.event.AgentAuditHistoryEventEntity;
import org.cibseven.bpm.engine.impl.persistence.AbstractHistoricManager;

/**
 * Removal-time maintenance and deletion for {@code ACT_HI_AGENT_AUDIT}.
 *
 * <p>The out-of-line payloads in {@code ACT_GE_BYTEARRAY} are not touched here: they carry their
 * own removal time and are removed by {@link ByteArrayManager} in the same cleanup pass.</p>
 */
public class AgentAuditManager extends AbstractHistoricManager {

  /**
   * Stamps the removal time on all audit entries of one process instance tree. Used by the
   * {@code end} removal-time strategy, where the time only becomes known once the root instance
   * finishes.
   */
  public DbOperation addRemovalTimeToAgentAuditByRootProcessInstanceId(String rootProcessInstanceId,
                                                                      Date removalTime,
                                                                      Integer batchSize) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("rootProcessInstanceId", rootProcessInstanceId);
    parameters.put("removalTime", removalTime);
    parameters.put("maxResults", batchSize);

    return getDbEntityManager()
      .updatePreserveOrder(AgentAuditHistoryEventEntity.class,
          "updateAgentAuditByRootProcessInstanceId", parameters);
  }

  /**
   * Stamps the removal time on the audit entries of a single process instance. Used by the
   * "set removal time" batch operation, which walks instances one by one.
   */
  public DbOperation addRemovalTimeToAgentAuditByProcessInstanceId(String processInstanceId,
                                                                  Date removalTime,
                                                                  Integer batchSize) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("processInstanceId", processInstanceId);
    parameters.put("removalTime", removalTime);
    parameters.put("maxResults", batchSize);

    return getDbEntityManager()
      .updatePreserveOrder(AgentAuditHistoryEventEntity.class,
          "updateAgentAuditByProcessInstanceId", parameters);
  }

  /**
   * @param minuteFrom start of this cleanup job's slice of the hour
   * @param minuteTo   end of that slice; when the slice spans all 60 minutes the minute filter is
   *                   omitted, because restricting to 0..59 would only add a function call on the
   *                   indexed column without excluding anything
   */
  public DbOperation deleteAgentAuditByRemovalTime(Date removalTime, int minuteFrom, int minuteTo,
                                                  int batchSize) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("removalTime", removalTime);
    if (minuteTo - minuteFrom + 1 < 60) {
      parameters.put("minuteFrom", minuteFrom);
      parameters.put("minuteTo", minuteTo);
    }
    parameters.put("batchSize", batchSize);

    return getDbEntityManager()
      .deletePreserveOrder(AgentAuditHistoryEventEntity.class, "deleteAgentAuditByRemovalTime",
        new ListQueryParameterObject(parameters, 0, batchSize));
  }

  /**
   * Removes the audit entries of the given process instances together with their out-of-line
   * payloads. The byte arrays go first: once the rows are gone their payload references are no
   * longer reachable and the byte arrays would stay behind as orphans.
   */
  public void deleteAgentAuditByProcessInstanceIds(List<String> processInstanceIds) {
    getDbEntityManager().deletePreserveOrder(ByteArrayEntity.class,
        "deleteAgentAuditByteArraysByProcessInstanceIds", processInstanceIds);
    getDbEntityManager().deletePreserveOrder(AgentAuditHistoryEventEntity.class,
        "deleteAgentAuditByProcessInstanceIds", processInstanceIds);
  }

}
