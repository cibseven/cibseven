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
import java.util.List;

import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.bpm.model.bpmn.instance.Activity;
import org.cibseven.bpm.model.bpmn.instance.AdHocSubProcess;
import org.cibseven.bpm.model.bpmn.instance.Documentation;
import org.cibseven.bpm.model.bpmn.instance.FlowElement;
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

    /** One startable activity. */
    public static final class Entry {

        private final String id;
        private final String name;
        private final String documentation;

        Entry(String id, String name, String documentation) {
            this.id = id;
            this.name = name;
            this.documentation = documentation;
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
                entries.add(new Entry(child.getId(), child.getName(), firstDocumentation(child)));
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
}
