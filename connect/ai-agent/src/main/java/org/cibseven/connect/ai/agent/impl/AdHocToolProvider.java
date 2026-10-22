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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.cibseven.connect.ai.agent.agentic.AdHocToolDescriptor;
import org.cibseven.connect.ai.agent.agentic.AgenticToolCatalogParser;
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
 * {@code toolParameter} calls in its input mappings (CIB7-2136), and nothing else. Three things
 * are refused before the activity starts, through the same exception channel every other tool
 * error takes back to the model: a value the declaration does not name, a value of the wrong
 * type, and a call that leaves out a parameter the declaration makes required — the model reads
 * the refusal and calls again.
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
                            variables(descriptor, arguments(descriptor, toolRequest.arguments()))));
                }
            });
        }
        tools.put(ToolSpecification.builder()
                // The constant, not a literal: AgentConnectorImpl#availableTools advertises the
                // same name to a model that made one up, and classifies a sibling call by it.
                // Two spellings that drifted would have completeScope itself read as invented.
                .name(AgenticToolCatalogParser.COMPLETE_SCOPE_TOOL)
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
     *
     * <p>An optional parameter is simply absent from {@code required} — fine for the default,
     * non-strict schema this connector sends today. OpenAI's <em>strict</em> tool mode requires
     * every property to be listed in {@code required} (optionality is expressed by also making
     * the type nullable); if this connector ever turns strict mode on, a declared-optional
     * parameter stops being expressible as written here and this method needs revisiting, not
     * just the request flag.
     */
    protected ToolSpecification specification(AdHocToolDescriptor descriptor) {
        ToolSpecification.Builder tool = ToolSpecification.builder()
                .name(descriptor.getActivityId())
                .description(description(descriptor));
        if (!descriptor.getParameters().isEmpty()) {
            JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
            List<String> required = new ArrayList<String>();
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
                if (parameter.isRequired()) {
                    required.add(parameter.getName());
                }
            }
            if (!required.isEmpty()) {
                schema.required(required);
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
        // What the call uses and leaves, before the model chooses it (CIB7-2169).
        appendNames(text, "Process data it reads: ", descriptor.getReadVariables());
        appendNames(text, "Process data it writes: ", descriptor.getResultVariables());
        text.append(" | Starts this activity of the ad hoc sub process. The result says whether it"
                + " finished during the call (its declared values are then included — the only turn"
                + " you see them) or waits for a person or another system (you get a further turn"
                + " when it finishes). Call it once per performance you want.");
        return text.toString();
    }

    /** Names shown in a description at most, so one tool cannot fill the prompt with them. */
    protected static final int MAX_NAMES = 10;

    protected static void appendNames(StringBuilder text, String label, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        text.append(' ').append(label);
        int shown = Math.min(names.size(), MAX_NAMES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(AdHocSubProcessTool.truncate(names.get(i),
                    AdHocSubProcessTool.MAX_NAME_CHARS));
        }
        if (names.size() > shown) {
            text.append(" and ").append(names.size() - shown).append(" more");
        }
        text.append('.');
    }

    /**
     * The call's arguments, restricted to the declared parameters.
     *
     * <p>An undeclared name is refused rather than dropped: silently dropping it would
     * have the model believe the value arrived. Types were promised to the model in the
     * schema; a value that still arrives with the wrong shape is refused, not coerced. A
     * required parameter that is absent, or passed as {@code null}, is refused as well;
     * an optional one is simply absent, and {@code toolCall.<name>} reads null.
     *
     * <p>All of it is refused at once, before the activity starts, in one message through
     * the exception channel: the model can fix the whole call in its next attempt rather
     * than one mistake at a time.
     */
    protected Map<String, Object> arguments(AdHocToolDescriptor descriptor, String argumentsJson) {
        Map<String, Object> raw = parse(argumentsJson);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        List<String> refusals = new ArrayList<String>();
        List<String> wronglyTyped = new ArrayList<String>();
        for (Map.Entry<String, Object> argument : raw.entrySet()) {
            AdHocToolDescriptor.Parameter declared = null;
            for (AdHocToolDescriptor.Parameter candidate : descriptor.getParameters()) {
                if (candidate.getName().equals(argument.getKey())) {
                    declared = candidate;
                    break;
                }
            }
            if (declared == null) {
                refusals.add("'" + descriptor.getActivityId() + "' has no parameter '"
                        + argument.getKey() + "'. Pass only the parameters the tool declares.");
                continue;
            }
            try {
                values.put(declared.getName(), typed(descriptor, declared, argument.getValue()));
            } catch (AdHocToolRefusedException e) {
                refusals.add(e.getMessage());
                wronglyTyped.add(declared.getName());
            }
        }
        String missing = missingRequiredParameters(descriptor, values, wronglyTyped);
        if (missing != null) {
            refusals.add(missing);
        }
        if (!refusals.isEmpty()) {
            throw new AdHocToolRefusedException(String.join(" ", refusals));
        }
        return values;
    }

    /**
     * Why the call lacks a required parameter, or null: every required parameter missing from
     * {@code values} or passed as {@code null} is named in one sentence. One passed with the
     * wrong type is not missing; its refusal says so already. A variable of the same name
     * visible on the scope does not stand in for it: the activity reads the value from
     * {@code toolCall}, not from that variable.
     */
    protected String missingRequiredParameters(AdHocToolDescriptor descriptor,
            Map<String, Object> values, List<String> wronglyTyped) {
        List<String> missing = new ArrayList<String>();
        for (AdHocToolDescriptor.Parameter parameter : descriptor.getParameters()) {
            if (parameter.isRequired() && values.get(parameter.getName()) == null
                    && !wronglyTyped.contains(parameter.getName())) {
                missing.add(parameter.getName());
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        boolean one = missing.size() == 1;
        return "'" + descriptor.getActivityId() + "' is missing required parameter"
                + (one ? " " : "s ") + quoted(missing) + ". Call again with "
                + (one ? "it" : "them") + " included.";
    }

    /** {@code [a, b, c]} rendered as {@code 'a', 'b', 'c'}. */
    protected static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    /**
     * The variables one call sets on its performance (CIB7-2136): a tool that declares a
     * parameter gets every argument in {@code toolCall}, which its input mapping reads as
     * {@code toolCall.<name>}. No argument is set under its own name, so a process variable of
     * that name stays visible. A tool without parameters gets no {@code toolCall} and no variable
     * of its own from the call.
     *
     * <p>{@code toolCall} is a Java-serialized {@link TreeMap} ({@link
     * AdHocToolDescriptor#toolCallValue}): its serialized form does not depend on how the map was
     * built, so reading it back in a later command does not write it again. A copied
     * {@link LinkedHashMap} keeps a table sized for its entries, which deserialization rebuilds
     * larger, so the engine's check for changed objects would see a change on every first read.
     */
    protected Map<String, Object> variables(AdHocToolDescriptor descriptor,
            Map<String, Object> arguments) {
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        if (!descriptor.getParameters().isEmpty()) {
            variables.put(AdHocToolDescriptor.TOOL_CALL_VARIABLE,
                    AdHocToolDescriptor.toolCallValue(arguments));
        }
        return variables;
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
        throw new AdHocToolRefusedException("'" + descriptor.getActivityId() + "' parameter '"
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
            throw new AdHocToolRefusedException(
                    "The tool arguments are not a JSON object: " + e.getMessage());
        }
    }

    /** A tool result for the model. JSON, like the automatic serialization of a {@code @Tool}. */
    protected static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            // The maps only carry strings, numbers, booleans and lists and maps of those:
            // a declared result passes AdHocResults.safe on either path, which shows a
            // file, bytes or a date as a placeholder (CIB7-2233). So this is a
            // programming error rather than a data problem.
            throw new IllegalStateException("Could not serialize a tool result", e);
        }
    }

    /** How many characters {@code value} takes in a tool result, as {@link #toJson} writes it. */
    static int writtenLength(Object value) {
        try {
            return JSON.writeValueAsString(value).length();
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize a tool result value", e);
        }
    }
}
