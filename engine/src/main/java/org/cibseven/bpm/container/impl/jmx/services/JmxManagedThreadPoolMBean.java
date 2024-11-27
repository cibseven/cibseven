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
package org.cibseven.bpm.container.impl.jmx.services;


/**
 * <p>MBean responsible for controlling a Thread Pool. The thread pool is used by the
 * JobExecutor component.</p>
 * 
 * @author Daniel Meyer
 *
 */
public interface JmxManagedThreadPoolMBean {

  public abstract int getQueueCount();

  public abstract long getCompletedTaskCount();

  public abstract long getTaskCount();

  public abstract int getLargestPoolSize();

  public abstract int getActiveCount();

  public abstract int getPoolSize();

  public abstract void purgeThreadPool();

  public abstract int getMaximumPoolSize();

  public abstract void setMaximumPoolSize(int maximumPoolSize);

  public abstract void setCorePoolSize(int corePoolSize);

}