/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.client.ext;

import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.impl.ExternalTaskClientBuilderImpl;
import org.cibseven.bpm.client.topic.impl.TopicSubscriptionManager;

/**
 * <p>A drop-in replacement for {@link ExternalTaskClientBuilderImpl} (used via
 * {@link ExternalTaskClient#create()} by default) that wires up a
 * {@link SyncedTopicSubscriptionManager} instead of the default
 * {@link TopicSubscriptionManager}.
 *
 * <p>Clients built with this builder behave exactly like the default client, with one
 * addition: local {@link org.cibseven.bpm.client.task.ExternalTask} objects are kept in sync
 * with the engine-side state changes caused by
 * {@link org.cibseven.bpm.client.task.ExternalTaskService#lock(org.cibseven.bpm.client.task.ExternalTask, long) lock},
 * {@link org.cibseven.bpm.client.task.ExternalTaskService#unlock(org.cibseven.bpm.client.task.ExternalTask) unlock},
 * {@link org.cibseven.bpm.client.task.ExternalTaskService#extendLock(org.cibseven.bpm.client.task.ExternalTask, long) extendLock}
 * and
 * {@link org.cibseven.bpm.client.task.ExternalTaskService#handleFailure(org.cibseven.bpm.client.task.ExternalTask, String, String, int, long) handleFailure}.
 *
 * <p>Usage:
 * <pre>
 * ExternalTaskClient client = new SyncedExternalTaskClientBuilder()
 *     .baseUrl("http://localhost:8080/engine-rest")
 *     .build();
 * </pre>
 *
 * @see SyncedExternalTaskServiceImpl
 */
public class SyncedExternalTaskClientBuilder extends ExternalTaskClientBuilderImpl implements ExternalTaskClientBuilder {

  @Override
  protected void initTopicSubscriptionManager() {
    topicSubscriptionManager = new SyncedTopicSubscriptionManager(engineClient, typedValues, lockDuration);
    topicSubscriptionManager.setBackoffStrategy(getBackoffStrategy());

    if (isBackoffStrategyDisabled) {
      topicSubscriptionManager.disableBackoffStrategy();
    }

    if (isAutoFetchingEnabled()) {
      topicSubscriptionManager.start();
    }
  }

}
