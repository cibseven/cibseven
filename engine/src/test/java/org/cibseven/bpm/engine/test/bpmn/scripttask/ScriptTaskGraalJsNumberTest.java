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
package org.cibseven.bpm.engine.test.bpmn.scripttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.INTEGRAL;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.LONG;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.NONE;

import java.util.Map;
import java.util.stream.Stream;

import javax.script.ScriptEngine;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager;
import org.cibseven.bpm.engine.impl.scripting.engine.DefaultScriptEngineResolver;
import org.cibseven.bpm.engine.impl.scripting.engine.ScriptEngineResolver;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;

/**
 * CIB7-2665: the Java type of numbers that a JavaScript script on GraalJS 25 hands to Java, per mode of
 * {@code javaScriptNumberNarrowing}, on both paths that create GraalJS ({@code useCibSevenNamespaceInScripting}
 * false and true). CIB seven 2.2 (GraalJS 21) stored an Integer for a Java long in int range.
 */
public class ScriptTaskGraalJsNumberTest extends AbstractScriptTaskTest {

  private static final String JAVASCRIPT = "javascript";

  private static final String SCRIPT = String.join("\n",
      "execution.setVariable('longValueOf', java.lang.Long.valueOf(7));",
      "execution.setVariable('longVariable', execution.getVariable('lng'));",
      "execution.setVariable('longBinding', lng);",
      "execution.setVariable('queryCount', execution.getProcessEngineServices().getRuntimeService()",
      "    .createProcessInstanceQuery().processDefinitionKey('noSuchProcess').count());",
      "execution.setVariable('longValueOfSum', java.lang.Long.valueOf(lng + 1));",
      "execution.setVariable('bigLong', big);",
      "execution.setVariable('longPlusOne', execution.getVariable('lng') + 1);",
      "execution.setVariable('intPlusOne', n + 1);",
      "execution.setVariable('doubleVariable', dbl);",
      "execution.setVariable('fraction', 10 / 4);",
      "var list = new java.util.ArrayList();",
      "for (var i = 1; i <= 6; i++) { list.add(i * 10); }",
      "list.remove(lng);",
      "execution.setVariable('listAfterRemove', String(list));");

  protected ScriptEngineResolver resolverBefore;
  protected String narrowingBefore;
  protected boolean useCibSevenNamespaceBefore;

  @BeforeEach
  public void rememberConfiguration() {
    resolverBefore = processEngineConfiguration.getScriptEngineResolver();
    narrowingBefore = processEngineConfiguration.getJavaScriptNumberNarrowing();
    useCibSevenNamespaceBefore = processEngineConfiguration.isUseCibSevenNamespaceInScripting();
  }

  @AfterEach
  public void restoreConfiguration() {
    processEngineConfiguration.setScriptEngineResolver(resolverBefore);
    processEngineConfiguration.setJavaScriptNumberNarrowing(narrowingBefore);
    processEngineConfiguration.setUseCibSevenNamespaceInScripting(useCibSevenNamespaceBefore);
  }

  /**
   * A fresh script engine manager: its GraalJS factory has a polyglot engine of its own. Graal JS rejects a second
   * host access on a polyglot engine whose contexts had another one (the other modes and tests).
   */
  protected void useMode(String mode, boolean useCibSevenNamespace) {
    processEngineConfiguration.setJavaScriptNumberNarrowing(mode);
    processEngineConfiguration.setUseCibSevenNamespaceInScripting(useCibSevenNamespace);
    processEngineConfiguration.setScriptEngineResolver(new DefaultScriptEngineResolver(new CamundaScriptEngineManager()));
  }

  protected VariableMap inputs() {
    return Variables.createVariables()
        .putValue("lng", 5L)
        .putValue("big", 1L << 40)
        .putValue("n", 5)
        .putValue("dbl", 3.0d);
  }

  static Stream<Arguments> paths() {
    return Stream.of(Arguments.of(false), Arguments.of(true));
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldStoreJavaLongsInIntRangeAsIntegerByDefault(boolean useCibSevenNamespace) {
    // given
    assertThat(new StandaloneInMemProcessEngineConfiguration().getJavaScriptNumberNarrowing()).isEqualTo(LONG);
    useMode(LONG, useCibSevenNamespace);
    deployProcess(JAVASCRIPT, SCRIPT);

    // when
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", inputs());

    // then: as in CIB seven 2.2
    Map<String, Object> variables = runtimeService.getVariables(processInstance.getId());
    assertThat(variables.get("longValueOf")).isEqualTo(7);
    assertThat(variables.get("longVariable")).isEqualTo(5);
    assertThat(variables.get("longBinding")).isEqualTo(5);
    assertThat(variables.get("queryCount")).isEqualTo(0);
    assertThat(variables.get("longValueOfSum")).isEqualTo(6);
    assertThat(variables.get("bigLong")).isEqualTo(1L << 40);
    assertThat(variables.get("intPlusOne")).isEqualTo(6);
    assertThat(variables.get("doubleVariable")).isEqualTo(3.0d);
    assertThat(variables.get("fraction")).isEqualTo(2.5d);
    // List.remove(int), not List.remove(Object)
    assertThat(variables.get("listAfterRemove")).isEqualTo("[10, 20, 30, 40, 50]");
    // unlike CIB seven 2.2 (Integer 6): arithmetic with a Java long gives a double in GraalJS 25
    assertThat(variables.get("longPlusOne")).isEqualTo(6.0d);
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldStoreIntegralNumbersInIntRangeAsIntegerInModeIntegral(boolean useCibSevenNamespace) {
    // given
    useMode(INTEGRAL, useCibSevenNamespace);
    deployProcess(JAVASCRIPT, SCRIPT);

    // when
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", inputs());

    // then
    Map<String, Object> variables = runtimeService.getVariables(processInstance.getId());
    assertThat(variables.get("longValueOf")).isEqualTo(7);
    assertThat(variables.get("longVariable")).isEqualTo(5);
    assertThat(variables.get("longBinding")).isEqualTo(5);
    assertThat(variables.get("queryCount")).isEqualTo(0);
    assertThat(variables.get("longValueOfSum")).isEqualTo(6);
    assertThat(variables.get("longPlusOne")).isEqualTo(6);
    assertThat(variables.get("bigLong")).isEqualTo(1L << 40);
    assertThat(variables.get("intPlusOne")).isEqualTo(6);
    assertThat(variables.get("fraction")).isEqualTo(2.5d);
    assertThat(variables.get("listAfterRemove")).isEqualTo("[10, 20, 30, 40, 50]");
    // unlike CIB seven 2.2 (Double 3.0): an integral double is an Integer in this mode
    assertThat(variables.get("doubleVariable")).isEqualTo(3);
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldKeepTheTypesOfGraalJsInModeNone(boolean useCibSevenNamespace) {
    // given
    useMode(NONE, useCibSevenNamespace);
    deployProcess(JAVASCRIPT, SCRIPT);

    // when
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", inputs());

    // then
    Map<String, Object> variables = runtimeService.getVariables(processInstance.getId());
    assertThat(variables.get("longValueOf")).isEqualTo(7L);
    assertThat(variables.get("longVariable")).isEqualTo(5L);
    assertThat(variables.get("longBinding")).isEqualTo(5L);
    assertThat(variables.get("queryCount")).isEqualTo(0L);
    assertThat(variables.get("longValueOfSum")).isEqualTo(6L);
    assertThat(variables.get("longPlusOne")).isEqualTo(6.0d);
    assertThat(variables.get("bigLong")).isEqualTo(1L << 40);
    assertThat(variables.get("intPlusOne")).isEqualTo(6);
    assertThat(variables.get("doubleVariable")).isEqualTo(3.0d);
    assertThat(variables.get("fraction")).isEqualTo(2.5d);
    assertThat(variables.get("listAfterRemove")).isEqualTo("[10, 20, 30, 40, 50]");
  }

  @Test
  @Deployment(resources =
      "org/cibseven/bpm/engine/test/bpmn/scripttask/ScriptTaskGraalJsNumberTest.testResultMappingListener.bpmn20.xml")
  public void shouldStoreJavaLongsAsIntegerFromResultVariableOutputMappingAndListener() {
    // given
    useMode(LONG, false);

    // when
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("numberProcess", inputs());

    // then
    Map<String, Object> variables = runtimeService.getVariables(processInstance.getId());
    assertThat(variables.get("fromResult")).isEqualTo(5);
    assertThat(variables.get("fromOutputMapping")).isEqualTo(5);
    assertThat(variables.get("fromListener")).isEqualTo(7);
  }

  @Test
  @Deployment(resources = "org/cibseven/bpm/engine/test/bpmn/scripttask/ScriptTaskGraalJsNumberTest.testDecision.dmn")
  public void shouldStoreJavaLongsAsIntegerInDecisionsWithJavaScript() {
    // given
    useMode(LONG, false);

    // when
    Object result = decisionService.evaluateDecisionTableByKey("numberDecision", inputs()).getSingleEntry();

    // then
    assertThat(result).isEqualTo(5);
  }

  @Test
  public void shouldKeepAGraalJsScriptEngineThatACustomResolverCreates() {
    // given: a resolver that creates GraalJS itself, as the user guide shows (Scripting, JavaScript Considerations)
    processEngineConfiguration.setJavaScriptNumberNarrowing(LONG);
    processEngineConfiguration.setUseCibSevenNamespaceInScripting(false);
    processEngineConfiguration.setScriptEngineResolver(new DefaultScriptEngineResolver(new CamundaScriptEngineManager()) {
      @Override
      protected ScriptEngine getJavaScriptScriptEngine(String language) {
        return GraalJSScriptEngine.create(null, Context.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(className -> true));
      }
    });
    deployProcess(JAVASCRIPT, "execution.setVariable('longVariable', execution.getVariable('lng'));");

    // when
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("testProcess", inputs());

    // then: the engine of the resolver, with the types of GraalJS
    assertThat(runtimeService.getVariable(processInstance.getId(), "longVariable")).isEqualTo(5L);
  }

  @Test
  public void shouldRejectAnUnknownMode() {
    assertThatThrownBy(() -> processEngineConfiguration.setJavaScriptNumberNarrowing("int"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("javaScriptNumberNarrowing must be one of 'long', 'integral', 'none', not 'int'");
  }

}
