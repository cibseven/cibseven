/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.connect.ai.agent.agentic;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cibseven.bpm.engine.impl.core.model.PropertyKey;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;

/**
 * One startable activity of an agentic ad hoc sub process, described as a tool for a
 * language model: what it is called, what it does, which values it may be given and
 * which variables it declares as its result.
 *
 * <p>Built at <em>parse time</em> from the model and stored on the scope's activity
 * under {@link #CATALOG}, so the catalogue exists before any instance runs and a
 * broken declaration refuses the deployment rather than a turn. Read at run time to
 * build one tool specification per entry — the activity <em>is</em> the tool, so a
 * wrong activity name is not expressible for the model at all.
 *
 * <p>Written and read at two different times: {@link AgenticAdHocParseListener} builds
 * the catalogue while the model is deployed, and the connector's
 * {@code AdHocToolProvider} reads it on every turn. Both live in this module; the
 * scope's activity is what the two share.
 */
public final class AdHocToolDescriptor {

    /**
     * Where the catalogue sits on the scope's activity: a list of these, in model
     * order, one per directly startable child.
     */
    public static final PropertyKey<List<AdHocToolDescriptor>> CATALOG =
            new PropertyKey<List<AdHocToolDescriptor>>("agenticToolCatalog");

    /**
     * The types a parameter may declare — JSON schema scalars, because that is what
     * a tool specification can say and what an activation can carry as a variable. In this
     * order, which is the order a deployment error lists them in.
     */
    public static final Set<String> PARAMETER_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("string", "integer", "number", "boolean")));

    /**
     * The local variable that carries every argument of one tool call, as a map from parameter
     * name to value (CIB7-2136), on the performance of a tool that declares a parameter. Its
     * input mapping reads it as {@code toolCall.<name>}, so what comes from the model is told
     * apart from what comes from the process, and no parameter shadows a process variable of
     * the same name. The name is reserved: no parameter may carry it.
     */
    public static final String TOOL_CALL_VARIABLE = "toolCall";

    /**
     * The value {@link #TOOL_CALL_VARIABLE} holds for a call's arguments: a {@link TreeMap},
     * Java-serialized whatever the engine's default serialization format. Under JSON a map read
     * back in a later command holds an Integer where the call set a Long, and a plain read of
     * {@code toolCall.<name>} hands that on; Java serialization keeps the types, and every engine
     * can write it, Spin or not, as it writes the chat log and the conversation. A {@link TreeMap}
     * has the same serialized form however it was built, so a read does not write it again.
     */
    public static ObjectValue toolCallValue(Map<String, Object> arguments) {
        return Variables.objectValue(new TreeMap<String, Object>(arguments))
                .serializationDataFormat(Variables.SerializationDataFormats.JAVA).create();
    }

    /**
     * Whether any tool of the catalogue declares a parameter (CIB7-2136): then the scope keeps
     * an empty {@link #TOOL_CALL_VARIABLE}, so a tool started by something
     * other than the agent reads its parameters as null instead of failing on the missing map.
     * Not where a {@link #TOOL_CALL_VARIABLE} is visible already: in a scope that is itself the
     * tool of an outer agent such a tool reads the outer call's map, and in a process with a
     * variable of that name, that variable.
     */
    public static boolean anyParameters(List<AdHocToolDescriptor> catalog) {
        if (catalog == null) {
            return false;
        }
        for (AdHocToolDescriptor tool : catalog) {
            if (!tool.getParameters().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One declared input parameter of a tool, declared with {@code toolParameter(toolCall.<name>,
     * ...)} in one of its input mappings (CIB7-2136).
     */
    public static final class Parameter {

        private final String name;
        private final String type;
        private final String description;
        private final boolean required;

        public Parameter(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }

        /** The parameter's name: its key in {@link #TOOL_CALL_VARIABLE}. */
        public String getName() {
            return name;
        }

        /** One of {@link #PARAMETER_TYPES}. */
        public String getType() {
            return type;
        }

        /** What the model is told the parameter means; may be empty, never null. */
        public String getDescription() {
            return description;
        }

        /**
         * Whether the model must supply this parameter. A required parameter is listed
         * in the tool schema's {@code required} array; a call that omits it, or passes
         * {@code null} for it, is refused before the activity starts.
         */
        public boolean isRequired() {
            return required;
        }
    }

    private final String activityId;
    private final String name;
    private final String documentation;
    private final List<String> readVariables;
    private final List<String> resultVariables;
    private final boolean blockedWhileOthersRun;
    private final List<Parameter> parameters;

    public AdHocToolDescriptor(String activityId, String name, String documentation,
            List<String> resultVariables, boolean blockedWhileOthersRun,
            List<Parameter> parameters) {
        this(activityId, name, documentation, Collections.<String>emptyList(), resultVariables,
                blockedWhileOthersRun, parameters);
    }

    public AdHocToolDescriptor(String activityId, String name, String documentation,
            List<String> readVariables, List<String> resultVariables,
            boolean blockedWhileOthersRun, List<Parameter> parameters) {
        this.activityId = activityId;
        this.name = name;
        this.documentation = documentation;
        this.readVariables = Collections.unmodifiableList(readVariables);
        this.resultVariables = Collections.unmodifiableList(resultVariables);
        this.blockedWhileOthersRun = blockedWhileOthersRun;
        this.parameters = Collections.unmodifiableList(parameters);
    }

    /** The BPMN activity id — the tool's name, and what an activation request names. */
    public String getActivityId() {
        return activityId;
    }

    /** The label from the diagram, or {@code null} when the element has none. */
    public String getName() {
        return name;
    }

    /** The element's documentation text, or {@code null} when it has none. */
    public String getDocumentation() {
        return documentation;
    }

    /**
     * The process variables this activity reads, as far as its model says: what its mappings
     * name (CIB7-2169). Shown to the model in the description, so it knows what a call uses.
     */
    public List<String> getReadVariables() {
        return readVariables;
    }

    /**
     * The process variables this activity declares as its result, in document order,
     * without duplicates. What the agent is shown when the activity finishes —
     * a declaration, not a measurement.
     */
    public List<String> getResultVariables() {
        return resultVariables;
    }

    /** Whether this activity may only start while nothing else in the scope runs. */
    public boolean isBlockedWhileOthersRun() {
        return blockedWhileOthersRun;
    }

    /** The declared input parameters, possibly empty, never null. */
    public List<Parameter> getParameters() {
        return parameters;
    }
}
