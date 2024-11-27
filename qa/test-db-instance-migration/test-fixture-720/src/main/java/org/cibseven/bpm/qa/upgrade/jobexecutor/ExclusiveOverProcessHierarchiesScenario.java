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

package org.cibseven.bpm.qa.upgrade.jobexecutor;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.qa.upgrade.DescribesScenario;
import org.cibseven.bpm.qa.upgrade.ScenarioSetup;

public class ExclusiveOverProcessHierarchiesScenario {

    @DescribesScenario("createRootProcessInstancesWithHierarchies")
    public static ScenarioSetup createRootProcessInstancesWithHierarchies() {
        return (engine, scenarioName) -> {
            var runtimeService = engine.getRuntimeService();

            deployRootProcessWithHierarchies(engine);

            // given the two 7.20 process instances
            runtimeService.startProcessInstanceByKey("rootProcess_7.20", "withMultipleHierarchies");
            runtimeService.startProcessInstanceByKey("rootProcess_7.20", "withMultipleHierarchies");
        };
    }

    protected static void deployRootProcessWithHierarchies(ProcessEngine engine) {

        // given
        var subModel = Bpmn.createExecutableProcess("subProcess_7.20")
                .camundaHistoryTimeToLive(180)
                .startEvent()
                .scriptTask("scriptTask")
                .camundaAsyncBefore()
                .camundaExclusive(true)
                .scriptFormat("javascript")
                .scriptText("print(execution.getJobs())")
                .endEvent()
                .done();

        var rootModel = Bpmn.createExecutableProcess("rootProcess_7.20")
                .camundaHistoryTimeToLive(180)
                .startEvent()
                .callActivity("callActivity")
                .calledElement("subProcess_7.20")
                .multiInstance()
                .parallel()
                .cardinality("2")
                .multiInstanceDone()
                .endEvent()
                .done();

        engine.getRepositoryService()
                .createDeployment()
                .addModelInstance("subProcess.bpmn", subModel)
                .addModelInstance("rootProcess.bpmn", rootModel)
                .deploy();
    }
}