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
package org.cibseven.bpm.model.cmmn.impl.instance;

import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN11_NS;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN_ATTRIBUTE_APPLICABILITY_RULE_REFS;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN_ATTRIBUTE_AUTHORIZED_ROLE_REFS;
import static org.cibseven.bpm.model.cmmn.impl.CmmnModelConstants.CMMN_ELEMENT_TABLE_ITEM;

import java.util.Collection;

import org.cibseven.bpm.model.cmmn.instance.ApplicabilityRule;
import org.cibseven.bpm.model.cmmn.instance.CmmnElement;
import org.cibseven.bpm.model.cmmn.instance.Role;
import org.cibseven.bpm.model.cmmn.instance.TableItem;
import org.cibseven.bpm.model.xml.ModelBuilder;
import org.cibseven.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.cibseven.bpm.model.xml.type.ModelElementTypeBuilder;
import org.cibseven.bpm.model.xml.type.reference.AttributeReferenceCollection;

/**
 * @author Roman Smirnov
 *
 */
public abstract class TableItemImpl extends CmmnElementImpl implements TableItem {

  protected static AttributeReferenceCollection<ApplicabilityRule> applicabilityRuleRefCollection;
  protected static AttributeReferenceCollection<Role> authorizedRoleRefCollection;

  public TableItemImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public Collection<ApplicabilityRule> getApplicabilityRule() {
    return applicabilityRuleRefCollection.getReferenceTargetElements(this);
  }

  public Collection<Role> getAuthorizedRoles() {
    return authorizedRoleRefCollection.getReferenceTargetElements(this);
  }

  public static void registerType(ModelBuilder modelBuilder) {
    ModelElementTypeBuilder typeBuilder = modelBuilder.defineType(TableItem.class, CMMN_ELEMENT_TABLE_ITEM)
        .namespaceUri(CMMN11_NS)
        .abstractType()
        .extendsType(CmmnElement.class);

    applicabilityRuleRefCollection = typeBuilder.stringAttribute(CMMN_ATTRIBUTE_APPLICABILITY_RULE_REFS)
        .idAttributeReferenceCollection(ApplicabilityRule.class, CmmnAttributeElementReferenceCollection.class)
        .build();

    authorizedRoleRefCollection = typeBuilder.stringAttribute(CMMN_ATTRIBUTE_AUTHORIZED_ROLE_REFS)
        .idAttributeReferenceCollection(Role.class, CmmnAttributeElementReferenceCollection.class)
        .build();

    typeBuilder.build();
  }

}