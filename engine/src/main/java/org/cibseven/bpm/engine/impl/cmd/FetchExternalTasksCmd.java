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
package org.cibseven.bpm.engine.impl.cmd;

import static org.cibseven.bpm.engine.impl.Direction.DESCENDING;
import static org.cibseven.bpm.engine.impl.ExternalTaskQueryProperty.PRIORITY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cibseven.bpm.engine.externaltask.LockedExternalTask;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.QueryOrderingProperty;
import org.cibseven.bpm.engine.impl.cfg.TransactionState;
import org.cibseven.bpm.engine.impl.db.DbEntity;
import org.cibseven.bpm.engine.impl.db.EnginePersistenceLogger;
import org.cibseven.bpm.engine.impl.db.entitymanager.OptimisticLockingListener;
import org.cibseven.bpm.engine.impl.db.entitymanager.OptimisticLockingResult;
import org.cibseven.bpm.engine.impl.db.entitymanager.operation.DbEntityOperation;
import org.cibseven.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.cibseven.bpm.engine.impl.externaltask.ExternalTaskLogger;
import org.cibseven.bpm.engine.impl.externaltask.LockedExternalTaskImpl;
import org.cibseven.bpm.engine.impl.externaltask.TopicFetchInstruction;
import org.cibseven.bpm.engine.impl.history.HistoryLevel;
import org.cibseven.bpm.engine.impl.history.event.HistoryEventTypes;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.cibseven.bpm.engine.impl.util.EnsureUtil;

/**
 * @author Thorben Lindhauer
 * @author Christopher Zell
 *
 */
public class FetchExternalTasksCmd implements Command<List<LockedExternalTask>> {

  protected static final EnginePersistenceLogger LOG = ProcessEngineLogger.PERSISTENCE_LOGGER;
  protected static final ExternalTaskLogger EXTERNAL_TASK_LOG = ProcessEngineLogger.EXTERNAL_TASK_LOGGER;

  protected String workerId;
  protected int maxResults;
  protected List<QueryOrderingProperty> orderingProperties;

  protected Map<String, TopicFetchInstruction> fetchInstructions;

  public FetchExternalTasksCmd(String workerId, int maxResults, Map<String, TopicFetchInstruction> instructions) {
    this(workerId, maxResults, instructions, false, Collections.emptyList());
  }

  public FetchExternalTasksCmd(String workerId,
                               int maxResults,
                               Map<String, TopicFetchInstruction> instructions,
                               boolean usePriority,
                               List<QueryOrderingProperty> orderingProperties) {
    this.workerId = workerId;
    this.maxResults = maxResults;
    this.fetchInstructions = instructions;
    this.orderingProperties = orderingPropertiesWithPriority(usePriority, orderingProperties);
  }

  @Override
  public List<LockedExternalTask> execute(CommandContext commandContext) {
    validateInput();

    for (TopicFetchInstruction instruction : fetchInstructions.values()) {
      instruction.ensureVariablesInitialized();
    }

    List<ExternalTaskEntity> externalTasks = commandContext
      .getExternalTaskManager()
      .selectExternalTasksForTopics(new ArrayList<>(fetchInstructions.values()), maxResults, orderingProperties);

    final List<LockedExternalTask> result = new ArrayList<>();
    final List<ExternalTaskEntity> fetchedEventTasks = new ArrayList<>();
    HistoryLevel historyLevel = commandContext.getProcessEngineConfiguration().getHistoryLevel();

    for (ExternalTaskEntity entity : externalTasks) {

      TopicFetchInstruction fetchInstruction = fetchInstructions.get(entity.getTopicName());

      // retrieve the execution first to detect concurrent modifications @https://jira.camunda.com/browse/CAM-10750
      ExecutionEntity execution = entity.getExecution(false);

      if (execution != null) {
        entity.lock(workerId, fetchInstruction.getLockDuration());

        LockedExternalTaskImpl resultTask = LockedExternalTaskImpl.fromEntity(
            entity,
            fetchInstruction.getVariablesToFetch(),
            fetchInstruction.isLocalVariables(),
            fetchInstruction.isDeserializeVariables(),
            fetchInstruction.isIncludeExtensionProperties()
        );

        result.add(resultTask);

        if (historyLevel.isHistoryEventProduced(HistoryEventTypes.EXTERNAL_TASK_FETCH, entity)) {
          fetchedEventTasks.add(entity);
        }
      } else {
        LOG.logTaskWithoutExecution(workerId);
      }
    }

    filterOnOptimisticLockingFailure(commandContext, result);
    produceFetchedEventsAfterCommit(commandContext, fetchedEventTasks, result);

    return result;
  }

  /**
   * Writes the 'fetched' history log only for the tasks this worker actually locked.
   *
   * A lock lost to a concurrent worker (or to a concurrent complete/delete) is removed from
   * the result by {@link #filterOnOptimisticLockingFailure} and the failed update is ignored.
   * That happens during the flush, where all inserts are executed before the updates - a
   * history event created together with the lock would therefore be committed even for a
   * lost lock. Hence the events are written after the commit in a separate transaction.
   */
  protected void produceFetchedEventsAfterCommit(CommandContext commandContext,
                                                 final List<ExternalTaskEntity> fetchedEventTasks,
                                                 final List<LockedExternalTask> result) {
    if (fetchedEventTasks.isEmpty()) {
      return;
    }

    final CommandExecutor commandExecutor = commandContext.getProcessEngineConfiguration()
        .getCommandExecutorTxRequiresNew();

    commandContext.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, context -> {
      // evaluated after the flush: the result no longer contains tasks with a lost lock
      Set<String> lockedTaskIds = new HashSet<>();
      for (LockedExternalTask lockedTask : result) {
        lockedTaskIds.add(lockedTask.getId());
      }

      final List<ExternalTaskEntity> lockedTasks = new ArrayList<>();
      for (ExternalTaskEntity task : fetchedEventTasks) {
        if (lockedTaskIds.contains(task.getId())) {
          lockedTasks.add(task);
        }
      }

      if (lockedTasks.isEmpty()) {
        return;
      }

      try {
        commandExecutor.execute(newCommandContext -> {
          for (ExternalTaskEntity task : lockedTasks) {
            task.produceHistoricExternalTaskFetchedEvent();
          }
          return null;
        });
      } catch (RuntimeException e) {
        // the locks are committed already, so the worker must still get its tasks
        EXTERNAL_TASK_LOG.couldNotProduceFetchedEvents(workerId, e);
      }
    });
  }

  protected void filterOnOptimisticLockingFailure(CommandContext commandContext, final List<LockedExternalTask> tasks) {
    commandContext.getDbEntityManager().registerOptimisticLockingListener(new OptimisticLockingListener() {

      @Override
      public Class<? extends DbEntity> getEntityType() {
        return ExternalTaskEntity.class;
      }

      @Override
      public OptimisticLockingResult failedOperation(DbOperation operation) {

        if (operation instanceof DbEntityOperation) {
          DbEntityOperation dbEntityOperation = (DbEntityOperation) operation;
          DbEntity dbEntity = dbEntityOperation.getEntity();

          boolean failedOperationEntityInList = false;

          Iterator<LockedExternalTask> it = tasks.iterator();
          while (it.hasNext()) {
            LockedExternalTask resultTask = it.next();
            if (resultTask.getId().equals(dbEntity.getId())) {
              it.remove();
              failedOperationEntityInList = true;
              break;
            }
          }

          // If the entity that failed with an OLE is not in the list,
          // we rethrow the OLE to the caller.
          if (!failedOperationEntityInList) {
            return OptimisticLockingResult.THROW;
          }

          // If the entity that failed with an OLE has been removed
          // from the list, we suppress the OLE.
          return OptimisticLockingResult.IGNORE;
        }

        // If none of the conditions are satisfied, this might indicate a bug,
        // so we throw the OLE.
        return OptimisticLockingResult.THROW;
      }
    });
  }

  protected void validateInput() {
    EnsureUtil.ensureNotNull("workerId", workerId);
    EnsureUtil.ensureGreaterThanOrEqual("maxResults", maxResults, 0);

    for (TopicFetchInstruction instruction : fetchInstructions.values()) {
      EnsureUtil.ensureNotNull("topicName", instruction.getTopicName());
      EnsureUtil.ensurePositive("lockTime", instruction.getLockDuration());
    }
  }

  protected List<QueryOrderingProperty> orderingPropertiesWithPriority(boolean usePriority,
                                                                       List<QueryOrderingProperty> queryOrderingProperties) {
    List<QueryOrderingProperty> results = new ArrayList<>();

    // Priority needs to be the first item in the list because it takes precedence over other sorting options
    // Multi level ordering works by going through the list of ordering properties from first to last item
    if (usePriority) {
      results.add(new QueryOrderingProperty(PRIORITY, DESCENDING));
    }

    results.addAll(queryOrderingProperties);

    return results;
  }
}