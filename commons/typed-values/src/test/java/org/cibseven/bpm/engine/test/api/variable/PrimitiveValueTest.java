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
package org.cibseven.bpm.engine.test.api.variable;

import static org.cibseven.bpm.engine.variable.Variables.booleanValue;
import static org.cibseven.bpm.engine.variable.Variables.byteArrayValue;
import static org.cibseven.bpm.engine.variable.Variables.createVariables;
import static org.cibseven.bpm.engine.variable.Variables.dateValue;
import static org.cibseven.bpm.engine.variable.Variables.doubleValue;
import static org.cibseven.bpm.engine.variable.Variables.integerValue;
import static org.cibseven.bpm.engine.variable.Variables.shortValue;
import static org.cibseven.bpm.engine.variable.Variables.stringValue;
import static org.cibseven.bpm.engine.variable.Variables.untypedNullValue;
import static org.cibseven.bpm.engine.variable.type.ValueType.BOOLEAN;
import static org.cibseven.bpm.engine.variable.type.ValueType.BYTES;
import static org.cibseven.bpm.engine.variable.type.ValueType.DATE;
import static org.cibseven.bpm.engine.variable.type.ValueType.DOUBLE;
import static org.cibseven.bpm.engine.variable.type.ValueType.INTEGER;
import static org.cibseven.bpm.engine.variable.type.ValueType.NULL;
import static org.cibseven.bpm.engine.variable.type.ValueType.SHORT;
import static org.cibseven.bpm.engine.variable.type.ValueType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Date;
import java.util.stream.Stream;

import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.impl.value.NullValueImpl;
import org.cibseven.bpm.engine.variable.type.ValueType;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Philipp Ossler *
 */
public class PrimitiveValueTest {

  protected static final Date DATE_VALUE = new Date();
  protected static final String LOCAL_DATE_VALUE = "2015-09-18";
  protected static final String LOCAL_TIME_VALUE = "10:00:00";
  protected static final String PERIOD_VALUE = "P14D";
  protected static final byte[] BYTES_VALUE = "a".getBytes();

  static Stream<Object[]> data() {
    return Arrays.stream(new Object[][] {
        { STRING, "someString", stringValue("someString"), stringValue(null) },
        { INTEGER, 1, integerValue(1), integerValue(null) },
        { BOOLEAN, true, booleanValue(true), booleanValue(null) },
        { NULL, null, untypedNullValue(), untypedNullValue() },
        { SHORT, (short) 1, shortValue((short) 1), shortValue(null) },
        { DOUBLE, 1d, doubleValue(1d), doubleValue(null) },
        { DATE, DATE_VALUE, dateValue(DATE_VALUE), dateValue(null) },
        { BYTES, BYTES_VALUE, byteArrayValue(BYTES_VALUE), byteArrayValue(null) }
      });
  }

  protected String variableName = "variable";

  @ParameterizedTest
  @MethodSource("data")
  public void testCreatePrimitiveVariableUntyped(ValueType valueType, Object value, TypedValue typedValue, TypedValue nullValue) {
    VariableMap variables = createVariables().putValue(variableName, value);

    assertEquals(value, variables.get(variableName));
    assertEquals(value, variables.getValueTyped(variableName).getValue());

    TypedValue tv = variables.getValueTyped(variableName);
    if (!(tv instanceof NullValueImpl)) {
      assertNull(tv.getType());
      assertEquals(variables.get(variableName), tv.getValue());
    } else {
      assertEquals(NULL, tv.getType());
    }
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testCreatePrimitiveVariableTyped(ValueType valueType, Object value, TypedValue typedValue, TypedValue nullValue) {
    VariableMap variables = createVariables().putValue(variableName, typedValue);

    assertEquals(value, variables.get(variableName));
    assertEquals(valueType, variables.getValueTyped(variableName).getType());
    Object stringValue = variables.getValueTyped(variableName).getValue();
    assertEquals(value, stringValue);
  }

  @ParameterizedTest
  @MethodSource("data")
  public void testCreatePrimitiveVariableNull(ValueType valueType, Object value, TypedValue typedValue, TypedValue nullValue) {
    VariableMap variables = createVariables().putValue(variableName, nullValue);

    assertEquals(null, variables.get(variableName));
    assertEquals(valueType, variables.getValueTyped(variableName).getType());
    Object stringValue = variables.getValueTyped(variableName).getValue();
    assertEquals(null, stringValue);
  }

}
