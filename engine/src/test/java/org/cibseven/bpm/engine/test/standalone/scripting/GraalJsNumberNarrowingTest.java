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
package org.cibseven.bpm.engine.test.standalone.scripting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.LONG;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.NONE;

import java.util.stream.Stream;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager;
import org.cibseven.bpm.engine.impl.scripting.engine.DefaultScriptEngineResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CIB7-2665: the script engines of {@code javaScriptNumberNarrowing} with the script engine resolvers of the
 * process engine, without a process engine: the configuration is the one of the command context.
 */
public class GraalJsNumberNarrowingTest {

  /** A Java parameter of type {@code Object}, as {@code VariableScope.setVariable(String, Object)}. */
  public static class Sink {
    protected Object value;

    public void set(Object value) {
      this.value = value;
    }
  }

  protected static ProcessEngineConfigurationImpl configuration(String mode) {
    return new StandaloneInMemProcessEngineConfiguration().setJavaScriptNumberNarrowing(mode);
  }

  /** As ProcessApplicationScriptEnvironment: one resolver for every process engine of a process application. */
  protected static DefaultScriptEngineResolver sharedResolver() {
    return new DefaultScriptEngineResolver(new CamundaScriptEngineManager());
  }

  /** @return what the script hands to {@code sink.set}, or else its result */
  protected static Object evaluate(DefaultScriptEngineResolver resolver, ProcessEngineConfigurationImpl configuration,
      boolean cache, String script) throws ScriptException {
    Context.setProcessEngineConfiguration(configuration);
    try {
      ScriptEngine scriptEngine = resolver.getScriptEngine("javascript", cache);
      Sink sink = new Sink();
      scriptEngine.getContext().setAttribute("sink", sink, ScriptContext.ENGINE_SCOPE);
      Object result = scriptEngine.eval(script);
      return sink.value != null ? sink.value : result;
    } finally {
      Context.removeProcessEngineConfiguration();
    }
  }

  static Stream<Arguments> modesOneAfterTheOther() {
    return Stream.of(
        Arguments.of(LONG, NONE, true),
        Arguments.of(NONE, LONG, true),
        Arguments.of(LONG, NONE, false),
        Arguments.of(NONE, LONG, false));
  }

  @ParameterizedTest(name = "{0} then {1}, cache {2}")
  @MethodSource("modesOneAfterTheOther")
  public void shouldServeTwoProcessEnginesWithDifferentModesFromOneResolver(String first, String second,
      boolean cache) throws ScriptException {
    // given: a process application on two process engines, one with each mode
    DefaultScriptEngineResolver resolver = sharedResolver();
    String script = "sink.set(java.lang.Long.valueOf(5))";

    // when
    Object fromFirst = evaluate(resolver, configuration(first), cache, script);
    Object fromSecond = evaluate(resolver, configuration(second), cache, script);

    // then: each with the type of its mode; GraalJS would reject a second host access on one polyglot engine
    assertThat(fromFirst).isEqualTo(LONG.equals(first) ? (Object) 5 : (Object) 5L);
    assertThat(fromSecond).isEqualTo(LONG.equals(second) ? (Object) 5 : (Object) 5L);
  }

}
