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
package org.cibseven.bpm.model.bpmn.impl.instance;

import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;

import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN20_NS;
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ELEMENT_LOOP_DATA_INPUT_REF;

/**
 * The BPMN 2.0 loopDataInputRef element of the BPMN 2.0
 * tMultiInstanceLoopCharacteristics type
 * 
 * @author Filip Hrisafov
 */
public class LoopDataInputRef extends BpmnModelElementInstanceImpl {

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder
        .defineType(LoopDataInputRef.class, BPMN_ELEMENT_LOOP_DATA_INPUT_REF)
        .namespaceUri(BPMN20_NS)
        .instanceProvider(
            new ModelElementTypeBuilder.ModelTypeInstanceProvider<LoopDataInputRef>() {
              public LoopDataInputRef newInstance(ModelTypeInstanceContext instanceContext) {
                return new LoopDataInputRef(instanceContext);
              }
            });

    typeBuilder.build();
  }

  public LoopDataInputRef(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }
}
