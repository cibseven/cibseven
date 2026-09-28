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
package org.cibseven.bpm.engine.impl.bpmn.behavior;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cibseven.bpm.engine.impl.core.model.PropertyKey;

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
 * <p>Lives in the engine because the writer and the reader share no other module: the
 * connect process engine plugin builds the catalogue while parsing, the AI agent
 * connector consumes it during a turn, and each knows the other only through the
 * connector registry. Same reasoning as {@link AdHocAgentState}.
 */
public final class AdHocToolDescriptor {

    /**
     * Where the catalogue sits on the scope's activity: a list of these, in model
     * order, one per directly startable child.
     */
    public static final PropertyKey<List<AdHocToolDescriptor>> CATALOG =
            new PropertyKey<List<AdHocToolDescriptor>>("agenticToolCatalog");

    /**
     * {@code camunda:property} prefix on a child declaring one input parameter:
     * {@code adHocToolParameter.<name>} with the value {@code <type>|<description>}.
     *
     * <p>Parameterless tools are the normal case — every parameter is a value that
     * travelled through the language model. This channel exists for the child that
     * genuinely needs one, and its scope is deliberately small: a type and a
     * description, nothing else, until running models show more is needed.
     */
    public static final String TOOL_PARAMETER_PREFIX = "adHocToolParameter.";

    /**
     * The types a parameter may declare — JSON schema scalars, because that is what
     * a tool specification can say and what an activation can carry as a variable.
     */
    public static final Set<String> PARAMETER_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("string", "integer", "number", "boolean")));

    /** One declared input parameter of a tool. */
    public static final class Parameter {

        private final String name;
        private final String type;
        private final String description;

        public Parameter(String name, String type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }

        /** The parameter's name — the variable the value is set as on the activation. */
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
    }

    private final String activityId;
    private final String name;
    private final String documentation;
    private final List<String> resultVariables;
    private final boolean blockedWhileOthersRun;
    private final List<Parameter> parameters;

    public AdHocToolDescriptor(String activityId, String name, String documentation,
            List<String> resultVariables, boolean blockedWhileOthersRun,
            List<Parameter> parameters) {
        this.activityId = activityId;
        this.name = name;
        this.documentation = documentation;
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
