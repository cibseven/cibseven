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
package org.cibseven.bpm.engine.impl.cmd;
import java.io.Serializable;
import java.util.Collection;

import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.impl.form.FormFieldDto;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.value.TypedValue;

import com.google.gson.JsonObject;

/**
 * @author  Daniel Meyer
 */
public abstract class AbstractGetFormVariablesCmd implements Command<VariableMap>, Serializable {

  private static final long serialVersionUID = 1L;

  public String resourceId;
  public Collection<String> formVariableNames;
  protected boolean deserializeObjectValues;
  protected boolean localVariablesOnly;

  public AbstractGetFormVariablesCmd(String resourceId, Collection<String> formVariableNames, boolean deserializeObjectValues, boolean localVariablesOnly) {
    this.resourceId = resourceId;
    this.formVariableNames = formVariableNames;
    this.deserializeObjectValues = deserializeObjectValues;
    this.localVariablesOnly = localVariablesOnly;
  }

  protected TypedValue createVariable(FormField formField, VariableScope variableScope) {
    TypedValue value = formField.getValue();

    if(value != null) {
      return value;
    }
    else {
      return null;
    }

  }

  protected Object createExtendedVariable(FormField formField, VariableScope variableScope) {
    if(formField.getValue() != null) {
      return new FormFieldDto(formField);
    }
    else {
      return null;
    }
  }

  protected Object createExtendedVariable(JsonObject jsonFormControl, Object variableValue) {
    return new FormFieldDto(jsonFormControl, variableValue);
  }
}
