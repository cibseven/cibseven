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
package org.cibseven.bpm.engine.impl.migration.instance;

import org.cibseven.bpm.engine.impl.jobexecutor.TimerDeclarationImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * @author Thorben Lindhauer
 *
 */
public class EmergingJobInstance implements EmergingInstance {

  protected TimerDeclarationImpl timerDeclaration;

  public EmergingJobInstance(TimerDeclarationImpl timerDeclaration) {
    this.timerDeclaration = timerDeclaration;
  }

  @Override
  public void create(ExecutionEntity scopeExecution) {
    timerDeclaration.createTimer(scopeExecution);
  }
}