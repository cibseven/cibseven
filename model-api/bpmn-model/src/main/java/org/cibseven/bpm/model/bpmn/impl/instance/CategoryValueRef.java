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
import static org.cibseven.bpm.model.bpmn.impl.BpmnModelConstants.BPMN_ELEMENT_CATEGORY_VALUE_REF;
import static org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;

/**
 * The BPMN categoryValueRef element of the BPMN tFlowElement type
 *
 * @author Sebastian Menski
 */
public class CategoryValueRef extends BpmnModelElementInstanceImpl {

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(CategoryValueRef.class, BPMN_ELEMENT_CATEGORY_VALUE_REF)
      .namespaceUri(BPMN20_NS)
      .instanceProvider(new ModelTypeInstanceProvider<CategoryValueRef>() {
        public CategoryValueRef newInstance(ModelTypeInstanceContext instanceContext) {
          return new CategoryValueRef(instanceContext);
        }
      });

    typeBuilder.build();
  }

  public CategoryValueRef(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }
}
