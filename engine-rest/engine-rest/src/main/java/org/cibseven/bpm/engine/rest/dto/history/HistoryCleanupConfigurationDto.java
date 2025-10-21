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

import java.util.Date;

public class HistoryCleanupConfigurationDto {

	protected Date batchWindowStartTime;
	protected Date batchWindowEndTime;
	protected boolean enabled;
	protected String historyCleanupStrategy;
	protected int historyCleanupBatchSize;
	protected int historyCleanupDegreeOfParallelism;
	protected String removalTimeStrategy;
	protected String removalTimeProvider;
	protected String databaseSchemaUpdate;
	protected boolean jobExecutorActivate;
	protected boolean jobExecutorDeploymentAware;
	protected boolean jobExecutorPreferTimerJobs;
	protected String historyCleanupJobLogTimeToLive;
	protected String taskMetricsTimeToLive;
	protected String batchOperationHistoryTimeToLive;
	protected String historyTimeToLive;
	protected boolean enforceHistoryTimeToLive;
	protected int historyCleanupBatchThreshold;
	protected boolean historyCleanupMetricsEnabled;

	public Date getBatchWindowStartTime() {
		return batchWindowStartTime;
	}

	public void setBatchWindowStartTime(Date batchWindowStartTime) {
		this.batchWindowStartTime = batchWindowStartTime;
	}

	public Date getBatchWindowEndTime() {
		return batchWindowEndTime;
	}

	public void setBatchWindowEndTime(Date batchWindowEndTime) {
		this.batchWindowEndTime = batchWindowEndTime;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getHistoryCleanupStrategy() {
		return historyCleanupStrategy;
	}

	public void setHistoryCleanupStrategy(String historyCleanupStrategy) {
		this.historyCleanupStrategy = historyCleanupStrategy;
	}

	public int getHistoryCleanupBatchSize() {
		return historyCleanupBatchSize;
	}

	public void setHistoryCleanupBatchSize(int historyCleanupBatchSize) {
		this.historyCleanupBatchSize = historyCleanupBatchSize;
	}

	public int getHistoryCleanupDegreeOfParallelism() {
		return historyCleanupDegreeOfParallelism;
	}

	public void setHistoryCleanupDegreeOfParallelism(int historyCleanupDegreeOfParallelism) {
		this.historyCleanupDegreeOfParallelism = historyCleanupDegreeOfParallelism;
	}

	public String getRemovalTimeStrategy() {
		return removalTimeStrategy;
	}

	public void setRemovalTimeStrategy(String removalTimeStrategy) {
		this.removalTimeStrategy = removalTimeStrategy;
	}

	public String getRemovalTimeProvider() {
		return removalTimeProvider;
	}

	public void setRemovalTimeProvider(String removalTimeProvider) {
		this.removalTimeProvider = removalTimeProvider;
	}

	public String getDatabaseSchemaUpdate() {
		return databaseSchemaUpdate;
	}

	public void setDatabaseSchemaUpdate(String databaseSchemaUpdate) {
		this.databaseSchemaUpdate = databaseSchemaUpdate;
	}

	public boolean isJobExecutorActivate() {
		return jobExecutorActivate;
	}

	public void setJobExecutorActivate(boolean jobExecutorActivate) {
		this.jobExecutorActivate = jobExecutorActivate;
	}

	public boolean isJobExecutorDeploymentAware() {
		return jobExecutorDeploymentAware;
	}

	public void setJobExecutorDeploymentAware(boolean jobExecutorDeploymentAware) {
		this.jobExecutorDeploymentAware = jobExecutorDeploymentAware;
	}

	public boolean isJobExecutorPreferTimerJobs() {
		return jobExecutorPreferTimerJobs;
	}

	public void setJobExecutorPreferTimerJobs(boolean jobExecutorPreferTimerJobs) {
		this.jobExecutorPreferTimerJobs = jobExecutorPreferTimerJobs;
	}

	public String getHistoryCleanupJobLogTimeToLive() {
		return historyCleanupJobLogTimeToLive;
	}

	public void setHistoryCleanupJobLogTimeToLive(String historyCleanupJobLogTimeToLive) {
		this.historyCleanupJobLogTimeToLive = historyCleanupJobLogTimeToLive;
	}

	public String getTaskMetricsTimeToLive() {
		return taskMetricsTimeToLive;
	}

	public void setTaskMetricsTimeToLive(String taskMetricsTimeToLive) {
		this.taskMetricsTimeToLive = taskMetricsTimeToLive;
	}

	public String getBatchOperationHistoryTimeToLive() {
		return batchOperationHistoryTimeToLive;
	}

	public void setBatchOperationHistoryTimeToLive(String batchOperationHistoryTimeToLive) {
		this.batchOperationHistoryTimeToLive = batchOperationHistoryTimeToLive;
	}

	public String getHistoryTimeToLive() {
		return historyTimeToLive;
	}

	public void setHistoryTimeToLive(String historyTimeToLive) {
		this.historyTimeToLive = historyTimeToLive;
	}

	public boolean isEnforceHistoryTimeToLive() {
		return enforceHistoryTimeToLive;
	}

	public void setEnforceHistoryTimeToLive(boolean enforceHistoryTimeToLive) {
		this.enforceHistoryTimeToLive = enforceHistoryTimeToLive;
	}

	public int getHistoryCleanupBatchThreshold() {
		return historyCleanupBatchThreshold;
	}

	public void setHistoryCleanupBatchThreshold(int historyCleanupBatchThreshold) {
		this.historyCleanupBatchThreshold = historyCleanupBatchThreshold;
	}

	public boolean isHistoryCleanupMetricsEnabled() {
		return historyCleanupMetricsEnabled;
	}

	public void setHistoryCleanupMetricsEnabled(boolean historyCleanupMetricsEnabled) {
		this.historyCleanupMetricsEnabled = historyCleanupMetricsEnabled;
	}

}
