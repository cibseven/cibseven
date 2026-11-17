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
package org.cibseven.connect.ai.agent.impl;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.cibseven.bpm.engine.impl.core.variable.mapping.OutputParameter;
import org.cibseven.bpm.engine.impl.core.variable.mapping.value.ParameterValueProvider;
import org.cibseven.bpm.engine.impl.el.ElValueProvider;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.connect.ai.agent.AgentConnector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How the AI-output marker ({@code ${outputAiMeta}}) is stored, after a service task and after an
 * agentic ad hoc sub process alike (CIB7-2819).
 *
 * <p>The marker is a map: {@code aiGenerated}, a Boolean, then Strings. In the engine's default
 * format it could not always be read back:
 * <ul>
 *   <li>with JSON and type validation, the allow list has no {@code Collections$UnmodifiableMap};
 *   <li>with JSON and Spin's map type detector, the type is named after the first value, a
 *       Boolean, and the Strings cannot be read as one.
 * </ul>
 * One unreadable variable fails every read of all of the instance's variables with it, and a task's
 * form variables.
 *
 * <p>So an output parameter that maps the marker as it is stores it in the engine's default
 * format, but under a type it names itself. With JSON, that is {@link #JSON_TYPE_NAME}: a
 * {@code LinkedHashMap} of untyped values, which type validation allows and no type detector
 * rewrites. With any other default format, the engine stores the map as before.
 */
public final class AiOutputMarker {

  /** The type the marker is stored under when the engine's default format is JSON. */
  public static final String JSON_TYPE_NAME =
      "java.util.LinkedHashMap<java.lang.String,java.lang.Object>";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** {@code ${outputAiMeta}}, blanks inside the braces allowed. */
  private static final Pattern THE_MARKER_AS_IT_IS = Pattern.compile(
      "\\$\\{\\s*" + Pattern.quote(AgentConnector.PARAM_NAME_OUTPUT_AI_META) + "\\s*\\}");

  private AiOutputMarker() {
  }

  /**
   * Has each output parameter in {@code io} that maps the marker as it is store it through
   * {@link #storable(Object)}. Every other parameter, {@code ${outputAiMeta.aiGenerated}} among
   * them, stays as it is.
   */
  public static void storeTheMarkerOf(IoMapping io) {
    if (io == null || io.getOutputParameters() == null) {
      return;
    }
    List<OutputParameter> outputs = io.getOutputParameters();
    for (int i = 0; i < outputs.size(); i++) {
      OutputParameter parameter = outputs.get(i);
      if (mapsTheMarkerAsItIs(parameter)) {
        outputs.set(i, new OutputParameter(parameter.getName(),
            new MarkerValueProvider(parameter.getValueProvider())));
      }
    }
  }

  /** Whether {@code parameter} maps {@code ${outputAiMeta}} and nothing else. */
  public static boolean mapsTheMarkerAsItIs(OutputParameter parameter) {
    ParameterValueProvider provider = parameter.getValueProvider();
    return provider instanceof ElValueProvider
        && ((ElValueProvider) provider).getExpression() != null
        && THE_MARKER_AS_IT_IS.matcher(
            ((ElValueProvider) provider).getExpression().getExpressionText().trim()).matches();
  }

  /** The value to store for the marker on the engine of the current command. */
  public static Object storable(Object marker) {
    ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
    return storable(marker,
        (configuration == null) ? null : configuration.getDefaultSerializationFormat());
  }

  /**
   * The value to store for the marker under {@code defaultSerializationFormat}: with JSON, the
   * marker as JSON under {@link #JSON_TYPE_NAME}; otherwise, or if it cannot be written as JSON,
   * the marker itself, for the engine's default format.
   */
  public static Object storable(Object marker, String defaultSerializationFormat) {
    if (!(marker instanceof Map)
        || !Variables.SerializationDataFormats.JSON.getName().equals(defaultSerializationFormat)) {
      return marker;
    }
    String json;
    try {
      json = JSON.writeValueAsString(marker);
    } catch (JsonProcessingException e) {
      return marker;
    }
    return Variables.serializedObjectValue(json)
        .serializationDataFormat(Variables.SerializationDataFormats.JSON)
        .objectTypeName(JSON_TYPE_NAME)
        .create();
  }

  /** The parameter's own value, made {@link #storable(Object)}. */
  static class MarkerValueProvider implements ParameterValueProvider {

    private final ParameterValueProvider delegate;

    MarkerValueProvider(ParameterValueProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object getValue(VariableScope variableScope) {
      return storable(delegate.getValue(variableScope));
    }

    @Override
    public boolean isDynamic() {
      return delegate.isDynamic();
    }

    /** The provider it wraps. */
    ParameterValueProvider getDelegate() {
      return delegate;
    }
  }
}
