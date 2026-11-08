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
package org.cibseven.bpm.integrationtest.functional.el;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * JUEL converts method arguments with its own rules on every server, as CIB seven 2.2 did,
 * not with the server's EL implementation (CIB7-2609).
 */
@ExtendWith(ArquillianExtension.class)
public class JuelMethodArgumentConversionTest extends AbstractFoxPlatformIntegrationTest {

  @Deployment
  public static WebArchive processArchive() {
    return initWebArchiveDeployment()
      .addAsResource("org/cibseven/bpm/integrationtest/functional/el/JuelMethodArgumentConversionTest.bpmn20.xml")
      .addAsResource("org/cibseven/bpm/integrationtest/functional/el/JuelMethodArgumentConversionTest.dmn");
  }

  @Test
  public void shouldConvertNullMethodArgumentWithJuel() {
    // when: the expression, the JUEL script and the JUEL input expression of the decision call
    // Long#compareTo(Long) with null. JUEL passes 0, so the result is 1. The EL 3+ implementations
    // of Tomcat, WildFly and JBoss EAP would pass null, and compareTo would throw.
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("juelMethodArgumentConversion",
        Variables.createVariables()
          .putValue("limit", 5L)
          .putValue("missing", null));

    // then
    Map<String, Object> variables = runtimeService.getVariables(processInstance.getId());
    assertThat(variables)
      .containsEntry("fromExpression", 1)
      .containsEntry("fromScript", 1)
      .containsEntry("fromDecision", 1);
  }

}
