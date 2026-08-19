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
package org.cibseven.bpm.engine.test.api.history.removaltime.cleanup;

import static org.apache.commons.lang3.time.DateUtils.addDays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.ProcessEngineConfiguration.HISTORY_CLEANUP_STRATEGY_REMOVAL_TIME_BASED;
import static org.cibseven.bpm.engine.ProcessEngineConfiguration.HISTORY_REMOVAL_TIME_STRATEGY_END;
import static org.cibseven.bpm.engine.impl.jobexecutor.historycleanup.HistoryCleanupHandler.MAX_BATCH_SIZE;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.history.DefaultHistoryRemovalTimeProvider;
import org.cibseven.bpm.engine.impl.history.event.AgentAuditHistoryEventEntity;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.util.ClockUtil;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/**
 * End-to-end check that AI agent audit entries take part in history cleanup: that the removal time
 * is stamped on them when the process instance ends, and that both the entry and its out-of-line
 * payload are removed once that time has passed.
 *
 * <p>The audit entry is injected directly through the history event handler because producing one
 * through BPMN would require the AI agent connector, which is not on the engine's test
 * classpath.</p>
 */
public class AgentAuditHistoryCleanupTest {

  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected static final String PROCESS_KEY = "agentAuditCleanupProcess";
  protected static final int TIME_TO_LIVE_DAYS = 5;

  protected static final Date END_DATE =
      new GregorianCalendar(2013, Calendar.MARCH, 18, 13, 0, 0).getTime();

  protected static final String PAYLOAD =
      "{\"messages\":[{\"role\":\"system\",\"content\":\"a prompt too long for a varchar column\"}]}";

  protected final BpmnModelInstance process = Bpmn.createExecutableProcess(PROCESS_KEY)
      .camundaHistoryTimeToLive(TIME_TO_LIVE_DAYS)
      .startEvent()
        .userTask("userTask")
      .endEvent().done();

  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected HistoryService historyService;
  protected ManagementService managementService;
  protected ProcessEngineConfigurationImpl engineConfiguration;

  protected final Set<String> insertedAuditIds = new HashSet<>();
  protected final Set<String> cleanupJobIds = new HashSet<>();

  @Before
  public void init() {
    runtimeService = engineRule.getRuntimeService();
    taskService = engineRule.getTaskService();
    historyService = engineRule.getHistoryService();
    managementService = engineRule.getManagementService();
    engineConfiguration = engineRule.getProcessEngineConfiguration();

    engineConfiguration
      .setHistoryRemovalTimeStrategy(HISTORY_REMOVAL_TIME_STRATEGY_END)
      .setHistoryRemovalTimeProvider(new DefaultHistoryRemovalTimeProvider())
      .initHistoryRemovalTime();

    engineConfiguration.setHistoryCleanupStrategy(HISTORY_CLEANUP_STRATEGY_REMOVAL_TIME_BASED);
    engineConfiguration.setHistoryCleanupBatchSize(MAX_BATCH_SIZE);
    engineConfiguration.setHistoryCleanupBatchWindowStartTime(null);
    engineConfiguration.setHistoryCleanupDegreeOfParallelism(1);
    engineConfiguration.setHistoryTimeToLive(null);
    engineConfiguration.initHistoryCleanup();
  }

  /**
   * Running cleanup jobs leaves job-log and meter-log rows behind, and a test that deliberately
   * keeps an audit entry leaves its payload behind. The engine's post-test database check rejects
   * both, so all of it is removed here.
   */
  @After
  public void tearDown() {
    ClockUtil.reset();

    for (String auditId : insertedAuditIds) {
      final AgentAuditHistoryEventEntity entry = findAuditEntry(auditId);
      if (entry == null) {
        continue;
      }
      execute(commandContext -> {
        if (entry.getPayloadByteArrayId() != null) {
          commandContext.getByteArrayManager().deleteByteArrayById(entry.getPayloadByteArrayId());
        }
        commandContext.getDbEntityManager().delete(entry);
        return null;
      });
    }
    insertedAuditIds.clear();

    for (String jobId : cleanupJobIds) {
      execute(commandContext -> {
        commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(jobId);
        JobEntity job = commandContext.getJobManager().findJobById(jobId);
        if (job != null) {
          commandContext.getJobManager().delete(job);
        }
        return null;
      });
    }
    cleanupJobIds.clear();

    for (Job job : historyService.findHistoryCleanupJobs()) {
      managementService.deleteJob(job.getId());
    }

    execute(commandContext -> {
      commandContext.getMeterLogManager().deleteAll();
      return null;
    });
  }

  @Test
  public void shouldStampRemovalTimeOnProcessInstanceEndAndThenCleanUpEntryAndPayload() {
    // given a running instance carrying one audit entry
    testRule.deploy(process);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String auditId = insertAuditEntry(processInstance.getId(), PAYLOAD);

    AgentAuditHistoryEventEntity inserted = findAuditEntry(auditId);
    assertThat(inserted).isNotNull();
    assertThat(inserted.getRemovalTime())
        .as("under the end strategy the removal time is not known while the instance runs")
        .isNull();

    String payloadByteArrayId = inserted.getPayloadByteArrayId();
    assertThat(payloadByteArrayId).isNotNull();
    assertThat(findByteArray(payloadByteArrayId)).isNotNull();

    // when the instance ends
    ClockUtil.setCurrentTime(END_DATE);
    taskService.complete(taskService.createTaskQuery().singleResult().getId());

    // then the removal time has been stamped on the audit entry as well
    AgentAuditHistoryEventEntity stamped = findAuditEntry(auditId);
    assertThat(stamped.getRemovalTime())
        .as("the end-strategy fan-out must reach ACT_HI_AGENT_AUDIT")
        .isEqualTo(addDays(END_DATE, TIME_TO_LIVE_DAYS));

    // when the removal time has passed and cleanup runs
    ClockUtil.setCurrentTime(addDays(END_DATE, TIME_TO_LIVE_DAYS + 1));
    runHistoryCleanup();

    // then entry and payload are both gone
    assertThat(findAuditEntry(auditId)).isNull();
    assertThat(findByteArray(payloadByteArrayId))
        .as("the payload byte array must not outlive the audit entry")
        .isNull();
  }

  @Test
  public void shouldCleanUpEntryWithoutPayload() {
    // given
    testRule.deploy(process);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String auditId = insertAuditEntry(processInstance.getId(), null);
    assertThat(findAuditEntry(auditId).getPayloadByteArrayId()).isNull();

    ClockUtil.setCurrentTime(END_DATE);
    taskService.complete(taskService.createTaskQuery().singleResult().getId());

    // when
    ClockUtil.setCurrentTime(addDays(END_DATE, TIME_TO_LIVE_DAYS + 1));
    runHistoryCleanup();

    // then
    assertThat(findAuditEntry(auditId)).isNull();
  }

  @Test
  public void shouldKeepEntryWhileRemovalTimeHasNotPassed() {
    // given
    testRule.deploy(process);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String auditId = insertAuditEntry(processInstance.getId(), PAYLOAD);

    ClockUtil.setCurrentTime(END_DATE);
    taskService.complete(taskService.createTaskQuery().singleResult().getId());

    // when cleanup runs one day before the removal time
    ClockUtil.setCurrentTime(addDays(END_DATE, TIME_TO_LIVE_DAYS - 1));
    runHistoryCleanup();

    // then
    AgentAuditHistoryEventEntity survivor = findAuditEntry(auditId);
    assertThat(survivor).as("entries must not be removed before their removal time").isNotNull();
    assertThat(findByteArray(survivor.getPayloadByteArrayId())).isNotNull();
  }

  @Test
  public void shouldRemoveEntryWhenProcessInstanceHistoryIsDeletedExplicitly() {
    // given
    testRule.deploy(process);
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

    String auditId = insertAuditEntry(processInstance.getId(), PAYLOAD);

    ClockUtil.setCurrentTime(END_DATE);
    taskService.complete(taskService.createTaskQuery().singleResult().getId());

    // when the history of the instance is deleted directly, bypassing removal time
    historyService.deleteHistoricProcessInstance(processInstance.getId());

    // then
    assertThat(findAuditEntry(auditId)).isNull();
  }

  /**
   * Routes an audit entry through the configured handler chain, exactly as the connector does, and
   * returns the id the engine assigned.
   */
  protected String insertAuditEntry(String processInstanceId, String payload) {
    AgentAuditHistoryEventEntity event = new AgentAuditHistoryEventEntity();
    event.setEventType(HistoryEventTypes.AGENT_AUDIT.getEventName());
    event.setAuditType("request");
    event.setRunId("run-" + processInstanceId);
    event.setEventSeq(0);
    event.setSchemaVersion(1);
    event.setTimestamp(ClockUtil.getCurrentTime());
    event.setProcessInstanceId(processInstanceId);
    // a top-level instance is its own root
    event.setRootProcessInstanceId(processInstanceId);
    event.setActivityId("agentTask");
    event.setProvider("OPEN_AI");
    event.setModel("gpt-test");
    if (payload != null) {
      event.setPayload(payload.getBytes(StandardCharsets.UTF_8));
    }

    execute(commandContext -> {
      engineConfiguration.getHistoryEventHandler().handleEvent(event);
      return null;
    });

    insertedAuditIds.add(event.getId());
    return event.getId();
  }

  protected AgentAuditHistoryEventEntity findAuditEntry(String id) {
    return execute(commandContext ->
        commandContext.getDbEntityManager().selectById(AgentAuditHistoryEventEntity.class, id));
  }

  protected ByteArrayEntity findByteArray(String byteArrayId) {
    if (byteArrayId == null) {
      return null;
    }
    return execute(commandContext ->
        commandContext.getDbEntityManager().selectById(ByteArrayEntity.class, byteArrayId));
  }

  protected List<Job> runHistoryCleanup() {
    historyService.cleanUpHistoryAsync(true);

    List<Job> jobs = historyService.findHistoryCleanupJobs();
    for (Job job : jobs) {
      cleanupJobIds.add(job.getId());
      managementService.executeJob(job.getId());
    }

    return jobs;
  }

  protected <T> T execute(Command<T> command) {
    return engineConfiguration.getCommandExecutorTxRequired().execute(command);
  }

}
