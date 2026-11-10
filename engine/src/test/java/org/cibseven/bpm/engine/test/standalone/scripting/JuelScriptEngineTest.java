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
package org.cibseven.bpm.engine.test.standalone.scripting;

import static org.assertj.core.api.Assertions.assertThat;

import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;

import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;

import org.cibseven.bpm.engine.impl.el.ProcessEngineElContext;
import org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngine;
import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/**
 * JUEL scripts must convert method arguments with JUEL, not with whatever EL implementation the
 * application happens to bring (CIB7-2609).
 */
public class JuelScriptEngineTest {

  @Test
  public void shouldCallMethodThatNeedsArgumentConversion() throws Exception {
    JuelScriptEngine engine = new JuelScriptEngine();
    ScriptContext scriptContext = new SimpleScriptContext();
    scriptContext.setAttribute("name", "Abc", ScriptContext.ENGINE_SCOPE);
    scriptContext.setAttribute("start", new DateTime(2026, 11, 6, 0, 0), ScriptContext.ENGINE_SCOPE);

    // the literal 1 is a Long; String#substring and DateTime#plusDays take an int
    assertThat(engine.eval("${name.substring(1)}", scriptContext)).isEqualTo("bc");
    assertThat(engine.eval("${start.plusDays(1).getDayOfMonth()}", scriptContext)).isEqualTo(7);
  }

  @Test
  public void shouldConvertNullMethodArgumentWithJuel() throws Exception {
    JuelScriptEngine engine = new JuelScriptEngine();
    ScriptContext scriptContext = new SimpleScriptContext();
    scriptContext.setAttribute("bean", new ArgumentBean(), ScriptContext.ENGINE_SCOPE);
    scriptContext.setAttribute("missing", null, ScriptContext.ENGINE_SCOPE);

    // JUEL converts null to 0 for a Long parameter, as CIB seven 2.2 did; EL 3+ would pass null
    assertThat(engine.eval("${bean.describe(missing)}", scriptContext)).isEqualTo("0");
  }

  @Test
  public void shouldPutTheJuelFactoryIntoTheContext() throws Exception {
    JuelScriptEngine engine = new JuelScriptEngine();
    ScriptContext scriptContext = new SimpleScriptContext();

    engine.eval("${1}", scriptContext);

    ELContext elContext = (ELContext) scriptContext.getAttribute("elcontext");
    assertThat(elContext.getContext(ExpressionFactory.class)).isInstanceOf(ExpressionFactoryImpl.class);
    // converts with that factory on every EL API, not only on the upstream jakarta.el-api
    assertThat(elContext).isInstanceOf(ProcessEngineElContext.class);
  }

  public static class ArgumentBean {
    public String describe(Long value) {
      return String.valueOf(value);
    }
  }

}
