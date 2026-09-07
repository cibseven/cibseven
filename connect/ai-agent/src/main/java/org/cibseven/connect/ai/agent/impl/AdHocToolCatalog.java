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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.instance.Activity;
import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.BusinessRuleTask;
import org.cibseven.bpm.model.bpmn.instance.Documentation;
import org.cibseven.bpm.model.bpmn.instance.ExtensionElements;
import org.cibseven.bpm.model.bpmn.instance.FlowElement;
import org.cibseven.bpm.model.bpmn.instance.ScriptTask;
import org.cibseven.bpm.model.bpmn.instance.ServiceTask;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFormData;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaFormField;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaInputOutput;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaOutputParameter;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaProperties;
import org.cibseven.bpm.model.bpmn.instance.cibseven.CamundaProperty;
import org.cibseven.bpm.model.xml.instance.ModelElementInstance;

/**
 * The activities of an ad hoc sub process that a caller may start, with the
 * information a model needs in order to choose between them.
 *
 * <h3>Why this reads the model rather than asking the engine</h3>
 * The parser computes the startable set at deployment and stores it on the
 * scope's activity, but that value is read only inside the engine, to validate
 * an incoming activation request. There is no public getter, no query and no
 * REST endpoint, so a caller has no way to ask what it may start.
 *
 * <p>This class therefore derives the set from the deployed model. The rule the
 * engine applies is "an activity with no incoming sequence flow from within the
 * scope". Sequence flows between children are rejected at parse time today, so
 * "every child activity" is currently the same set.
 *
 * <p><b>That equivalence is a deviation waiting to happen.</b> If inner sequence
 * flows become supported, this class will offer activities the engine refuses to
 * start. The fix is an engine-side API for the computed set; until then the
 * duplication is deliberate and recorded here.
 */
public final class AdHocToolCatalog {

    /**
     * {@code camunda:property} on a child activity naming the process variables it
     * produces, comma separated.
     *
     * <p>An override, not the normal case. The names are derived from the model
     * first — see {@link #resultVariables(FlowElement)} — and this property is for
     * the activity whose writes no declaration describes, or for narrowing a
     * derivation that offers more than the agent should see.
     */
    public static final String RESULT_VARIABLES_PROPERTY = "adHocResultVariables";

    /** One startable activity. */
    public static final class Entry {

        private final String id;
        private final String name;
        private final String documentation;
        private final List<String> resultVariables;

        Entry(String id, String name, String documentation, List<String> resultVariables) {
            this.id = id;
            this.name = name;
            this.documentation = documentation;
            this.resultVariables = resultVariables;
        }

        /** The BPMN activity id — this is what an activation request names. */
        public String getId() {
            return id;
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
         * The process variables this activity is expected to write, in document
         * order and without duplicates. Empty when the model declares nothing that
         * says so.
         *
         * <p>A structural expectation, not a measurement: it says what the model
         * declares, not what the activity actually wrote. For an activity that has
         * already run, the history knows the truth — see
         * {@code AdHocSubProcessTool}, which prefers it and falls back to this.
         */
        public List<String> getResultVariables() {
            return resultVariables;
        }
    }

    private AdHocToolCatalog() {
        // utility class
    }

    /**
     * Reads the catalogue of the ad hoc sub process {@code adHocActivityId} in the
     * process definition {@code processDefinitionId}.
     *
     * <p>The model comes from the engine's deployment cache, so repeating this per
     * turn does not re-parse the XML.
     *
     * @throws IllegalArgumentException when the id names no element, or names an
     *     element that is not an ad hoc sub process. Both are configuration
     *     errors, and a caller that silently received an empty catalogue would
     *     conclude the scope has nothing to start.
     */
    public static List<Entry> read(RepositoryService repositoryService,
                                   String processDefinitionId, String adHocActivityId) {

        BpmnModelInstance model = repositoryService.getBpmnModelInstance(processDefinitionId);
        ModelElementInstance element = model.getModelElementById(adHocActivityId);

        if (element == null) {
            throw new IllegalArgumentException("Process definition '" + processDefinitionId
                    + "' has no element with id '" + adHocActivityId + "'.");
        }
        if (!(element instanceof AdHocSubProcess)) {
            throw new IllegalArgumentException("Element '" + adHocActivityId + "' in process definition '"
                    + processDefinitionId + "' is a " + element.getElementType().getTypeName()
                    + ", not an ad hoc sub process, so it has no startable activities.");
        }

        List<Entry> entries = new ArrayList<>();
        for (FlowElement child : ((AdHocSubProcess) element).getFlowElements()) {
            // Only an Activity can be started directly. Gateways and intermediate
            // events are reachable by sequence flow, never by direct activation, so
            // offering them would produce a request the engine refuses.
            if (child instanceof Activity) {
                entries.add(new Entry(child.getId(), child.getName(), firstDocumentation(child),
                        resultVariables(child)));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * The first documentation entry of {@code element}, or {@code null}.
     *
     * <p>BPMN allows several documentation elements per element. Only the first is
     * used, because this text goes into a prompt and concatenating an unbounded
     * number of them is a size problem rather than added information.
     */
    private static String firstDocumentation(FlowElement element) {
        for (Documentation documentation : element.getDocumentations()) {
            String text = documentation.getTextContent();
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        }
        return null;
    }

    /**
     * The process variables {@code element} is expected to write.
     *
     * <h3>Why this is derived rather than declared</h3>
     * Asking the modeller to list an activity's result variables duplicates what
     * the model usually already says. Three declarations in ordinary use name
     * exactly those variables, so they are read instead:
     *
     * <ul>
     * <li>the names of {@code camunda:outputParameter} entries, which is how an
     *     activity's result is mapped into the process</li>
     * <li>{@code camunda:resultVariable} on a service, script or business rule
     *     task, which names where its return value goes</li>
     * <li>the ids of {@code camunda:formField} entries on a user task, which
     *     become process variables when the task is completed</li>
     * </ul>
     *
     * <p>{@link #RESULT_VARIABLES_PROPERTY} overrides all three when present, for
     * an activity whose writes none of them describe — a delegate calling
     * {@code setVariable} directly — or to narrow what the agent is shown.
     *
     * <h3>What this is not</h3>
     * It is not a judgement about relevance. The engine knows no domain meaning,
     * so it cannot tell an amount that decides the next step from an eye colour
     * that does not. What it can tell is which variables an activity is declared
     * to write, and a variable the activity never touches does not appear here at
     * all — which is the filtering that matters in practice.
     */
    private static List<String> resultVariables(FlowElement element) {
        List<String> override = declaredOverride(element);
        if (!override.isEmpty()) {
            return override;
        }

        // A set, because an output parameter and a result variable can name the
        // same variable, and reporting it twice would be noise. Insertion ordered,
        // so the order stays the document order a reader would expect.
        Set<String> names = new LinkedHashSet<>();
        for (CamundaInputOutput inputOutput : extensions(element, CamundaInputOutput.class)) {
            for (CamundaOutputParameter parameter : inputOutput.getCamundaOutputParameters()) {
                addIfPresent(names, parameter.getCamundaName());
            }
        }
        if (element instanceof ServiceTask) {
            addIfPresent(names, ((ServiceTask) element).getCamundaResultVariable());
        } else if (element instanceof ScriptTask) {
            addIfPresent(names, ((ScriptTask) element).getCamundaResultVariable());
        } else if (element instanceof BusinessRuleTask) {
            addIfPresent(names, ((BusinessRuleTask) element).getCamundaResultVariable());
        }
        if (element instanceof UserTask) {
            for (CamundaFormData formData : extensions(element, CamundaFormData.class)) {
                for (CamundaFormField field : formData.getCamundaFormFields()) {
                    addIfPresent(names, field.getCamundaId());
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    /** The names from {@link #RESULT_VARIABLES_PROPERTY}, or an empty list. */
    private static List<String> declaredOverride(FlowElement element) {
        for (CamundaProperties properties : extensions(element, CamundaProperties.class)) {
            for (CamundaProperty property : properties.getCamundaProperties()) {
                if (!RESULT_VARIABLES_PROPERTY.equals(property.getCamundaName())) {
                    continue;
                }
                String raw = property.getCamundaValue();
                if (raw == null || raw.trim().isEmpty()) {
                    return Collections.emptyList();
                }
                Set<String> names = new LinkedHashSet<>();
                for (String part : raw.split(",")) {
                    addIfPresent(names, part);
                }
                return Collections.unmodifiableList(new ArrayList<>(names));
            }
        }
        return Collections.emptyList();
    }

    /**
     * The extension elements of {@code element} of the given type.
     *
     * <p>{@code list()} rather than {@code singleResult()}: the schema permits one
     * of each of these blocks, but a hand-edited model can carry two, and a
     * catalogue read is not the place to fail over it.
     */
    private static <T extends ModelElementInstance> List<T> extensions(
            FlowElement element, Class<T> type) {
        ExtensionElements extensionElements = element.getExtensionElements();
        if (extensionElements == null) {
            return Collections.emptyList();
        }
        return extensionElements.getElementsQuery().filterByType(type).list();
    }

    private static void addIfPresent(Set<String> names, String candidate) {
        if (candidate != null && !candidate.trim().isEmpty()) {
            names.add(candidate.trim());
        }
    }
}
