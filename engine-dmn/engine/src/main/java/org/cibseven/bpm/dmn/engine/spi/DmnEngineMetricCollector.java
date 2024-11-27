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
package org.cibseven.bpm.dmn.engine.spi;

import org.cibseven.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationListener;

/**
 * DMN engine metric collector which records the executed decision elements
 * since since its creation.
 */
public interface DmnEngineMetricCollector extends DmnDecisionTableEvaluationListener {

  /**
   * @return the number of executed decision instances since creation of this engine
   */
  long getExecutedDecisionInstances();

  /**
   * @return the number of executed decision elements since creation of this engine
   */
  long getExecutedDecisionElements();

  /**
   * Resets the executed decision elements to 0.
   *
   * @return the number of executed decision elements before resetting.
   */
  long clearExecutedDecisionElements();

  /**
   * Resets the executed decision instances to 0.
   *
   * @return the number of executed decision elements before resetting.
   */
  long clearExecutedDecisionInstances();

}