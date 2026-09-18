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
package org.cibseven.bpm.quarkus.engine.extension.impl;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * A {@link JobExecutor} implementation that utilises a separate thread pool
 * to acquire and execute jobs.
 */
public class ManagedJobExecutor extends JobExecutor {

  protected ManagedExecutor taskExecutor;

  /**
   * CIB7-1959 Quarkus job executor creates threads without the Quarkus TCCL (SRCFG00015)
   *
   * <p>The Quarkus class loader, used as thread context class loader while jobs are executed.
   *
   * @see QuarkusExecuteJobsRunnable
   */
  protected ClassLoader contextClassLoader;

  /**
   * Constructs a new QuarkusJobExecutor with the provided
   * {@link ManagedExecutor} instance.
   */
  public ManagedJobExecutor(ManagedExecutor taskExecutor) {
    this(taskExecutor, null);
  }

  /**
   * CIB7-1959: constructs a new QuarkusJobExecutor with the provided {@link ManagedExecutor}
   * instance, executing jobs with {@code contextClassLoader} as thread context class loader.
   */
  public ManagedJobExecutor(ManagedExecutor taskExecutor, ClassLoader contextClassLoader) {
    this.taskExecutor = taskExecutor;
    this.contextClassLoader = contextClassLoader;
  }

  @Override
  protected void startExecutingJobs() {
    try {
      this.taskExecutor.execute(acquireJobsRunnable);
    } catch (Exception e) {
      throw new ProcessEngineException("Could not schedule AcquireJobsRunnable for execution.", e);
    }
  }

  @Override
  protected void stopExecutingJobs() {
    // nothing to do, the AcquireJobsRunnable instance will
    // be stopped when the ManagedExecutor instance is shut down.
  }

  /**
   * CIB7-1959: executes jobs with the Quarkus class loader instead of the process engine's,
   * so that configuration stays resolvable from job executor threads.
   */
  @Override
  public Runnable getExecuteJobsRunnable(List<String> jobIds, ProcessEngineImpl processEngine) {
    return new QuarkusExecuteJobsRunnable(jobIds, processEngine, contextClassLoader);
  }

  @Override
  public void executeJobs(List<String> jobIds, ProcessEngineImpl processEngine) {
    try {
      taskExecutor.execute(getExecuteJobsRunnable(jobIds, processEngine));
    } catch (RejectedExecutionException e) {

      logRejectedExecution(processEngine, jobIds.size());
      rejectedJobsHandler.jobsRejected(jobIds, processEngine, this);
    }
  }

}
