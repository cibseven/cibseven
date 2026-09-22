/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.rest.dto.runtime;

import java.util.Map;

import org.cibseven.bpm.engine.rest.dto.VariableValueDto;

/**
 * One element of an ad hoc sub process to activate, with the variables to set on it.
 */
public class AdHocActivityReferenceDto {

  private String elementId;
  private Map<String, VariableValueDto> variables;

  public String getElementId() {
    return elementId;
  }

  public void setElementId(String elementId) {
    this.elementId = elementId;
  }

  /**
   * Variables applied locally to the execution created for this element.
   *
   * <p>The engine keys them per element definition rather than per activation, so naming the same
   * element twice in one request starts it twice but gives both performances the variables of the
   * last entry. Send one request per performance to vary them.
   */
  public Map<String, VariableValueDto> getVariables() {
    return variables;
  }

  public void setVariables(Map<String, VariableValueDto> variables) {
    this.variables = variables;
  }
}
