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
package org.cibseven.bpm.model.bpmn.instance.camunda;

import org.cibseven.bpm.model.bpmn.instance.BpmnModelElementInstance;

import java.util.Collection;

/**
 * The BPMN formProperty camunda extension element
 *
 * @author Sebastian Menski
 */
public interface CamundaFormProperty extends BpmnModelElementInstance {

  String getCamundaId();

  void setCamundaId(String camundaId);

  String getCamundaName();

  void setCamundaName(String camundaName);

  String getCamundaType();

  void setCamundaType(String camundaType);

  boolean isCamundaRequired();

  void setCamundaRequired(boolean isCamundaRequired);

  boolean isCamundaReadable();

  void setCamundaReadable(boolean isCamundaReadable);

  boolean isCamundaWriteable();

  void setCamundaWriteable(boolean isCamundaWriteable);

  String getCamundaVariable();

  void setCamundaVariable(String camundaVariable);

  String getCamundaExpression();

  void setCamundaExpression(String camundaExpression);

  String getCamundaDatePattern();

  void setCamundaDatePattern(String camundaDatePattern);

  String getCamundaDefault();

  void setCamundaDefault(String camundaDefault);

  Collection<CamundaValue> getCamundaValues();

}