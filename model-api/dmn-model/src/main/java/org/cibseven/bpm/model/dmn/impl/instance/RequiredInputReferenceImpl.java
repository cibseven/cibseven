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
package org.cibseven.bpm.model.dmn.impl.instance;

import static org.cibseven.bpm.model.dmn.impl.DmnModelConstants.LATEST_DMN_NS;
import static org.cibseven.bpm.model.dmn.impl.DmnModelConstants.DMN_ELEMENT_REQUIRED_INPUT;

import org.cibseven.bpm.model.dmn.instance.DmnElementReference;
import org.cibseven.bpm.model.dmn.instance.RequiredInputReference;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;

public class RequiredInputReferenceImpl extends DmnElementReferenceImpl implements RequiredInputReference {

  public RequiredInputReferenceImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(RequiredInputReference.class, DMN_ELEMENT_REQUIRED_INPUT)
      .namespaceUri(LATEST_DMN_NS)
      .extendsType(DmnElementReference.class)
      .instanceProvider(new ModelTypeInstanceProvider<RequiredInputReference>() {
        public RequiredInputReference newInstance(ModelTypeInstanceContext instanceContext) {
          return new RequiredInputReferenceImpl(instanceContext);
        }
      });

    typeBuilder.build();
  }

}
