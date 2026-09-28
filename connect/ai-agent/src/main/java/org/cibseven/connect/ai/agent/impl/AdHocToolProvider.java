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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.impl.bpmn.behavior.AdHocToolDescriptor;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

/**
 * The tools of an agentic ad hoc sub process: one per startable child, plus
 * {@code completeScope}. The activity <em>is</em> the tool — a wrong activity name is
 * not expressible for the model, because there is no generic "start something" call
 * to put it in.
 *
 * <p>Built from the parse-time catalogue on the scope's activity, so what the model is
 * offered is exactly what the deployment validated. A tool's parameters are the
 * child's declared {@code adHocToolParameter.*} entries and nothing else: a value the
 * declaration does not name cannot be passed, and a value of the wrong type is
 * refused through the same exception channel every other tool error takes back to
 * the model.
 *
 * <p>The execution stays in {@link AdHocSubProcessTool}: this class only translates a
 * tool call into that layer's {@code startActivity}/{@code completeScope}, which is
 * where the per-turn call cap, the blocking rule and the completion bookkeeping live.
 */
public class AdHocToolProvider implements ToolProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecutionEntity scopeExecution;

    public AdHocToolProvider(ExecutionEntity scopeExecution) {
        this.scopeExecution = scopeExecution;
    }

    /** Whether {@code execution} sits on an agentic scope — its activity carries a catalogue. */
    public static boolean isAgenticScope(ExecutionEntity execution) {
        if (execution == null || execution.getActivity() == null) {
            return false;
        }
        List<AdHocToolDescriptor> catalog = ((ScopeImpl) execution.getActivity())
                .getProperties().get(AdHocToolDescriptor.CATALOG);
        return catalog != null;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        Map<ToolSpecification, ToolExecutor> tools =
                new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (final AdHocToolDescriptor descriptor : AdHocSubProcessTool.catalog(scopeExecution)) {
            tools.put(specification(descriptor), new ToolExecutor() {
                @Override
                public String execute(ToolExecutionRequest toolRequest, Object memoryId) {
                    return toJson(new AdHocSubProcessTool().startActivity(
                            descriptor.getActivityId(),
                            arguments(descriptor, toolRequest.arguments())));
                }
            });
        }
        tools.put(ToolSpecification.builder()
                .name("completeScope")
                .description(AdHocSubProcessTool.COMPLETE_SCOPE_DESCRIPTION)
                .build(),
                new ToolExecutor() {
                    @Override
                    public String execute(ToolExecutionRequest toolRequest, Object memoryId) {
                        return toJson(new AdHocSubProcessTool().completeScope());
                    }
                });
        return new ToolProviderResult(tools);
    }

    /**
     * The tool specification of one catalogue entry: the id as the name, the label and
     * documentation as the description, the declared parameters as the schema.
     */
    protected ToolSpecification specification(AdHocToolDescriptor descriptor) {
        ToolSpecification.Builder tool = ToolSpecification.builder()
                .name(descriptor.getActivityId())
                .description(description(descriptor));
        if (!descriptor.getParameters().isEmpty()) {
            JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
            for (AdHocToolDescriptor.Parameter parameter : descriptor.getParameters()) {
                if ("integer".equals(parameter.getType())) {
                    schema.addIntegerProperty(parameter.getName(), parameter.getDescription());
                } else if ("number".equals(parameter.getType())) {
                    schema.addNumberProperty(parameter.getName(), parameter.getDescription());
                } else if ("boolean".equals(parameter.getType())) {
                    schema.addBooleanProperty(parameter.getName(), parameter.getDescription());
                } else {
                    schema.addStringProperty(parameter.getName(), parameter.getDescription());
                }
            }
            tool.parameters(schema.build());
        }
        return tool.build();
    }

    /**
     * What the model is told the tool does: the diagram label and the element's
     * documentation, plus the two behaviours every activity tool shares. Name and
     * documentation are model data, which is why the turn's data note calls them out.
     */
    protected String description(AdHocToolDescriptor descriptor) {
        StringBuilder text = new StringBuilder();
        if (descriptor.getName() != null && !descriptor.getName().trim().isEmpty()) {
            // Capped like every other model-supplied string that reaches the prompt:
            // both fields are unbounded in the model, and one element must not be
            // able to dominate the context and its cost.
            text.append(AdHocSubProcessTool.truncate(descriptor.getName().trim(),
                    AdHocSubProcessTool.MAX_NAME_CHARS));
        }
        if (descriptor.getDocumentation() != null) {
            if (text.length() > 0) {
                text.append(": ");
            }
            text.append(AdHocSubProcessTool.truncate(descriptor.getDocumentation(),
                    AdHocSubProcessTool.MAX_DOCUMENTATION_CHARS));
        }
        if (text.length() == 0) {
            text.append("Starts the activity '").append(descriptor.getActivityId()).append("'.");
        }
        text.append(" | Starts this activity of the ad hoc sub process. The result says whether it"
                + " finished during the call (its declared values are then included — the only turn"
                + " you see them) or waits for a person or another system (you get a further turn"
                + " when it finishes). Call it once per performance you want.");
        return text.toString();
    }

    /**
     * The call's arguments, restricted to the declared parameters.
     *
     * <p>An undeclared name is refused rather than dropped: silently dropping it would
     * have the model believe the value arrived. A missing declared parameter is simply
     * absent — required-ness is deliberately not part of the first step's scope.
     * Types were promised to the model in the schema; a value that still arrives with
     * the wrong shape is refused through the exception channel, not coerced.
     */
    protected Map<String, Object> arguments(AdHocToolDescriptor descriptor, String argumentsJson) {
        Map<String, Object> raw = parse(argumentsJson);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> argument : raw.entrySet()) {
            AdHocToolDescriptor.Parameter declared = null;
            for (AdHocToolDescriptor.Parameter candidate : descriptor.getParameters()) {
                if (candidate.getName().equals(argument.getKey())) {
                    declared = candidate;
                    break;
                }
            }
            if (declared == null) {
                throw new AgentConnectorException("'" + descriptor.getActivityId()
                        + "' has no parameter '" + argument.getKey() + "'. Pass only the parameters"
                        + " the tool declares.");
            }
            values.put(declared.getName(), typed(descriptor, declared, argument.getValue()));
        }
        return values;
    }

    /** One value checked against its declared type. Refused, never coerced. */
    protected Object typed(AdHocToolDescriptor descriptor, AdHocToolDescriptor.Parameter declared,
            Object value) {
        if (value == null) {
            return null;
        }
        String type = declared.getType();
        if ("string".equals(type) && value instanceof String) {
            return value;
        }
        if ("boolean".equals(type) && value instanceof Boolean) {
            return value;
        }
        if ("integer".equals(type) && (value instanceof Integer || value instanceof Long)) {
            return ((Number) value).longValue();
        }
        if ("number".equals(type) && value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new AgentConnectorException("'" + descriptor.getActivityId() + "' parameter '"
                + declared.getName() + "' must be a " + type + ", but '" + value
                + "' is a " + value.getClass().getSimpleName() + ".");
    }

    protected static Map<String, Object> parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return JSON.readValue(argumentsJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new AgentConnectorException(
                    "The tool arguments are not a JSON object: " + e.getMessage());
        }
    }

    /** A tool result for the model. JSON, like the automatic serialization of a {@code @Tool}. */
    protected static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            // The maps only carry strings, numbers and lists of those, so this is a
            // programming error rather than a data problem.
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }
}
