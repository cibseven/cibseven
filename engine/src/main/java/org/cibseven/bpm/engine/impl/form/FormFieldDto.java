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

package org.cibseven.bpm.engine.impl.form;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.FormFieldValidationConstraint;
import org.cibseven.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl.BooleanValueImpl;
import org.cibseven.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl.DateValueImpl;
import org.cibseven.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl.DoubleValueImpl;
import org.cibseven.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl.IntegerValueImpl;
import org.cibseven.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl.StringValueImpl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class FormFieldDto {
  protected Object value;
  protected Map<String, String> validationConstraints = new HashMap<String, String>();
  protected Map<String, String> properties = new HashMap<String, String>();

  public FormFieldDto(FormField formField) {
    value = formField.getValue();
    properties = formField.getProperties();
    for (FormFieldValidationConstraint constraint : formField.getValidationConstraints()) {
      String constraintValue = null;
      if (constraint.getConfiguration() != null) {
        // TODO: is configuration always a String?
        constraintValue = (String) constraint.getConfiguration();
      }
      validationConstraints.put(constraint.getName(), constraintValue);
    }
  }

  public FormFieldDto(JsonObject jsonFormControl, Object variableValue) {

    boolean isDouble = false;
    // TODO: variable value could be a string for all available types which needs to be parsed
    // but can it also be a double/integer/boolean/date?
    String variableValueString = variableValue instanceof String ? (String)variableValue : null;
    String type = jsonFormControl.get("type").getAsString();
    if (type.equals("checkbox")) {
      boolean scopeValue = variableValue != null && variableValue instanceof Boolean ? (Boolean)variableValue : false;
      value = new BooleanValueImpl(scopeValue);
    } else if (type.equals("select") || type.equals("textfield")) {
      String scopeValue = variableValueString != null  ? variableValueString : "";
      value = new StringValueImpl(scopeValue);
    }
    else if (type.equals("number")) {
      int digits = 0;
      if (jsonFormControl.has("decimalDigits")) {
        digits = jsonFormControl.get("decimalDigits").getAsInt();
      }
      if (digits > 0) {
        isDouble = true;
        Double scopeValue = 0.0;
        if (variableValueString != null) {
          try {
            scopeValue = Double.parseDouble(variableValueString);
          } catch (NumberFormatException e) {
            //no handling required
          }
        } else if (variableValue != null && variableValue instanceof Double ) {
          scopeValue = (Double)variableValue;
        }
        value = new DoubleValueImpl(scopeValue);
      }
      else {
        int scopeValue = variableValue != null && variableValue instanceof Integer? (Integer)variableValue : 0;
        value = new IntegerValueImpl(scopeValue);
      }
    } else if (type.equals("datetime")) {
      //TODO: check handling of date values
      Date scopeValue = variableValue != null && variableValue instanceof Date ? (Date)variableValue : new Date();
      value = new DateValueImpl(scopeValue);
    }
    
    if (jsonFormControl.has("validate")) {
      JsonObject validate = jsonFormControl.get("validate").getAsJsonObject();
      if (validate.has("min")) 
        validationConstraints.put("min", String.valueOf(isDouble ? validate.get("min").getAsDouble() : validate.get("min").getAsInt()));
      if (validate.has("max")) 
        validationConstraints.put("max", String.valueOf(isDouble ? validate.get("max").getAsDouble() : validate.get("max").getAsInt()));
      if (validate.has("required")) 
        validationConstraints.put("max", String.valueOf(validate.get("required").getAsBoolean()));
    }
    if (jsonFormControl.has("properties")) {
      JsonObject jsonProperties = jsonFormControl.get("properties").getAsJsonObject();
      
      for (Map.Entry<String, JsonElement> entry : jsonProperties.entrySet()) {
        if (entry.getValue() != null)
          properties.put(entry.getKey(), entry.getValue().getAsString());
      }
    }

  }

  public Object getValue() {
    return value;
  }

  public void setValue(Object val) {
    value = val;
  }

  public Map<String, String> getValidationConstraints() {
    return validationConstraints;
  }

  public Map<String, String> getProperties() {
    return properties;
  }
}
