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
package org.cibseven.bpm.engine.test.bpmn.scripttask;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager.CAMUNDA_NAMESPACE;
import static org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager.CIBSEVEN_NAMESPACE;
import static org.junit.Assert.assertEquals;
import java.util.List;

import org.cibseven.bpm.engine.ScriptCompilationException;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.junit.Test;

/**
 *
 * @author Daniel Meyer (Javascript)
 * @author Sebastian Menski (Python)
 * @author Nico Rehwaldt (Ruby)
 * @author Christian Lipphardt (Groovy)
 *
 */
public class ScriptTaskGroovyTest extends AbstractScriptTaskTest {

  private static final String GROOVY = "groovy";
  
  @Test
  public void shouldLoadCibSevenClassGroovy() {

    // Save current configuration
    boolean useCibSevenNamespace = processEngineConfiguration.isUseCibSevenNamespaceInScripting();
    boolean useScriptEngineCaching = processEngineConfiguration.getScriptingEngines().isEnableScriptEngineCaching();
    
    // Reset caching to avoid getting already cached engine and set name-space option 
    processEngineConfiguration.getScriptingEngines().setEnableScriptEngineCaching(false);
    processEngineConfiguration.setUseCibSevenNamespaceInScripting(true);
    
    
    String cibsevenPackage = BpmnError.class.getPackageName();
    String camundaPackage = cibsevenPackage.replace(CIBSEVEN_NAMESPACE, CAMUNDA_NAMESPACE);
    String existingCommunityPackage = org.camunda.community.BpmnError.class.getPackageName();
    String wrongPackage = "org.wrongpackage";

    List<String[]> packages = List.of(
        new String[] { camundaPackage, cibsevenPackage },
        new String[] { cibsevenPackage, cibsevenPackage },
        new String[] { existingCommunityPackage, existingCommunityPackage }, 
        new String[] { wrongPackage, null } // should not be accessible
    );

    for (String[] tuple : packages) {

      String processPackage = tuple[0];
      String expectedPackage = tuple[1];

      final String expectedClass = BpmnError.class.getSimpleName();
      final String expectedMessage = "ServiceTaskError";

      final String errorMessageVar = "errorMessage";
      final String errorClassVar = "errorClass";
      final String errorPackageVar = "errorPackage";

      // Given
      String scriptText = "try {\n" + "  def error = new " + processPackage + "." + expectedClass + "(\""
          + expectedMessage + "\");\n" + "  def message = error.getErrorCode() ?: \"Default error code\";\n"
          + "  execution.setVariable('" + errorClassVar + "', error.getClass().getName());\n"
          + "  execution.setVariable('" + errorPackageVar + "', error.getClass().getPackage().getName());\n"
          + "  execution.setVariable('" + errorMessageVar + "', message);\n"
          + "} catch (Exception e) { execution.setVariable('" + errorMessageVar + "', e.message);}\n";

      deployProcess(GROOVY, scriptText);

      if (expectedPackage != null) {
        
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("testProcess");
        assertEquals(expectedMessage, runtimeService.getVariable(pi.getId(), errorMessageVar));
        assertEquals(expectedPackage + "." + expectedClass, runtimeService.getVariable(pi.getId(), errorClassVar));
        assertEquals(expectedPackage, runtimeService.getVariable(pi.getId(), errorPackageVar));
      } else {
        
        // When
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("testProcess"))
            // Then
            .isInstanceOf(ScriptCompilationException.class)
            .hasMessageContaining("Unable to compile script");
      }
    }
    
    // Restore configuration
    processEngineConfiguration.setUseCibSevenNamespaceInScripting(useCibSevenNamespace);
    processEngineConfiguration.getScriptingEngines().setEnableScriptEngineCaching(useScriptEngineCaching);
  }
}
