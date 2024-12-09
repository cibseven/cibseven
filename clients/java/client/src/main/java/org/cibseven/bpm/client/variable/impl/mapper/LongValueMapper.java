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
package org.cibseven.bpm.client.variable.impl.mapper;

import org.cibseven.bpm.client.variable.impl.TypedValueField;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.impl.value.UntypedValueImpl;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.LongValue;

public class LongValueMapper extends NumberValueMapper<LongValue> {

  public LongValueMapper() {
    super(ValueType.LONG);
  }

  public LongValue convertToTypedValue(UntypedValueImpl untypedValue) {
    return Variables.longValue((Long) untypedValue.getValue());
  }

  public void writeValue(LongValue longValue, TypedValueField typedValueField) {
    typedValueField.setValue(longValue.getValue());
  }

  public LongValue readValue(TypedValueField typedValueField) {
    Long longValue = null;

    Object value = typedValueField.getValue();
    if (value != null) {
      Number numValue = (Number) value;
      longValue = numValue.longValue();
    }

    return Variables.longValue(longValue);
  }

}
