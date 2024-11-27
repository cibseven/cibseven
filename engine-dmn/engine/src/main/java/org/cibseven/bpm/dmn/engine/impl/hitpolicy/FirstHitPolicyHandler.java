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
package org.cibseven.bpm.dmn.engine.impl.hitpolicy;

import java.util.Collections;

import org.cibseven.bpm.dmn.engine.delegate.DmnDecisionTableEvaluationEvent;
import org.cibseven.bpm.dmn.engine.delegate.DmnEvaluatedDecisionRule;
import org.cibseven.bpm.dmn.engine.impl.delegate.DmnDecisionTableEvaluationEventImpl;
import org.cibseven.bpm.dmn.engine.impl.spi.hitpolicy.DmnHitPolicyHandler;
import org.cibseven.bpm.model.dmn.HitPolicy;


public class FirstHitPolicyHandler implements DmnHitPolicyHandler {
  protected static final HitPolicyEntry HIT_POLICY = new HitPolicyEntry(HitPolicy.FIRST, null);

  public DmnDecisionTableEvaluationEvent apply(DmnDecisionTableEvaluationEvent decisionTableEvaluationEvent) {
    if (!decisionTableEvaluationEvent.getMatchingRules().isEmpty()) {
      DmnEvaluatedDecisionRule firstMatchedRule = decisionTableEvaluationEvent.getMatchingRules().get(0);
      ((DmnDecisionTableEvaluationEventImpl) decisionTableEvaluationEvent).setMatchingRules(Collections.singletonList(firstMatchedRule));
    }
    return decisionTableEvaluationEvent;
  }

  @Override
  public HitPolicyEntry getHitPolicyEntry() {
    return HIT_POLICY;
  }

  @Override
  public String toString() {
    return "FirstHitPolicyHandler{}";
  }

}