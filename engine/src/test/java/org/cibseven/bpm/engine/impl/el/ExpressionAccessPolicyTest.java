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
package org.cibseven.bpm.engine.impl.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.el.BeanELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.FunctionMapper;
import jakarta.el.MapELResolver;
import jakarta.el.PropertyNotFoundException;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.impl.juel.ExpressionAccessDeniedException;
import org.cibseven.bpm.impl.juel.ExpressionAccessPolicy;
import org.cibseven.bpm.impl.juel.ExpressionFactoryImpl;
import org.cibseven.bpm.impl.juel.ExpressionHardeningMode;
import org.cibseven.bpm.impl.juel.RestrictedELResolver;
import org.cibseven.bpm.impl.juel.SimpleContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The deny list for expressions (CIB7-2652), on a plain JUEL context.
 */
public class ExpressionAccessPolicyTest {

  /** Removes every default entry: the policy then denies the protected types only. */
  protected static final String WITHOUT_DEFAULTS =
      "-" + ExpressionAccessPolicy.DEFAULT_DENIED_EXPRESSION_ACCESS.replace(",", ",-");
  protected static final List<String> DEFAULTS =
      Arrays.asList(ExpressionAccessPolicy.DEFAULT_DENIED_EXPRESSION_ACCESS.split(","));

  /** In report mode, getClass() is then reported once, not also its Class result and the access on it. */
  protected static final String REPORT_GETTER_ONLY = "-java.lang.Class";

  protected final ExpressionFactoryImpl factory = new ExpressionFactoryImpl();

  static Stream<String> classAccessNotations() {
    return Stream.of(
        "${''.getClass()}",
        "${''.class}",
        "${''['class']}",
        "${''['getClass']()}",
        "${''[property]}",
        "${''[method]()}",
        "${''.getClass().forName('java.lang.System')}");
  }

  @ParameterizedTest
  @MethodSource("classAccessNotations")
  public void shouldDenyClassAccessByDefault(String expression) {
    assertThatThrownBy(() -> evaluate(ExpressionAccessPolicy.DEFAULT, expression))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("deniedExpressionAccess entry");
  }

  @Test
  public void shouldDenyClassAccessOnBeansAndMaps() {
    assertThatThrownBy(() -> evaluate(ExpressionAccessPolicy.DEFAULT, "${bean.getClass()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("'getClass' on " + Bean.class.getName());
    assertThatThrownBy(() -> evaluate(ExpressionAccessPolicy.DEFAULT, "${map.getClass()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldAllowOrdinaryAccess() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.DEFAULT;

    assertThat(evaluate(policy, "${'abc'.toUpperCase()}")).isEqualTo("ABC");
    assertThat(evaluate(policy, "${'abc'.length()}")).isEqualTo(3);
    assertThat(evaluate(policy, "${bean.name}")).isEqualTo("bean");
    assertThat(evaluate(policy, "${bean.getName()}")).isEqualTo("bean");
    assertThat(evaluate(policy, "${bean.toString()}")).isEqualTo("Bean");
    assertThat(evaluate(policy, "${bean.hashCode() == bean.hashCode()}")).isEqualTo(true);
    assertThat(evaluate(policy, "${map.key}")).isEqualTo("value");
  }

  @Test
  public void shouldRemoveDefaultEntryWithMinus() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(" -getClass , -class , -java.lang.Class ",
        ExpressionHardeningMode.ENFORCE);

    assertThat(policy.getDeniedEntries()).doesNotContain("getClass", "class", "java.lang.Class");
    assertThat(evaluate(policy, "${''.getClass().simpleName}")).isEqualTo("String");
  }

  @Test
  public void shouldAddEntriesToDefaults() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.System, ,fn:echo,-getClass",
        ExpressionHardeningMode.ENFORCE);

    List<String> expected = new ArrayList<>(DEFAULTS);
    expected.remove("getClass");
    expected.add("java.lang.System");
    expected.add("fn:echo");
    assertThat(policy.getDeniedEntries()).containsExactlyElementsOf(expected);
  }

  @Test
  public void shouldKeepDefaultsWithoutConfiguration() {
    assertThat(ExpressionAccessPolicy.create(null, null).getDeniedEntries()).containsExactlyElementsOf(DEFAULTS);
    assertThat(ExpressionAccessPolicy.create("", null).getDeniedEntries()).containsExactlyElementsOf(DEFAULTS);
    assertThat(ExpressionAccessPolicy.create(null, null).getMode()).isEqualTo(ExpressionHardeningMode.ENFORCE);
  }

  @Test
  public void shouldMatchNamesInFull() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("exec,get*Services", ExpressionHardeningMode.ENFORCE);

    assertThat(evaluate(policy, "${bean.execution}")).isEqualTo("execution");
    assertThatThrownBy(() -> evaluate(policy, "${bean.exec()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'exec'");
    assertThatThrownBy(() -> evaluate(policy, "${bean.getProcessEngineServices()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'get*Services'");
  }

  @Test
  public void shouldCheckPropertyAgainstItsGetterName() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("getProcessEngineServices", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.processEngineServices}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("'processEngineServices'");
    // a map key is data, not a getter
    assertThat(evaluate(policy, "${map.processEngineServices}")).isEqualTo("mapValue");
  }

  @Test
  public void shouldDenyTypeOfTargetIncludingSupertypesAndPackages() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(Denied.class.getPackage().getName() + ".ExpressionAccessPolicyTest$DeniedBase",
        ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${denied.name}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining(Denied.class.getName());
    assertThat(evaluate(policy, "${bean.name}")).isEqualTo("bean");

    ExpressionAccessPolicy packagePolicy = ExpressionAccessPolicy.create("java.lang.reflect", ExpressionHardeningMode.ENFORCE);
    assertThatThrownBy(() -> evaluate(packagePolicy, "${bean.method.name}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("java.lang.reflect.Method")
        .hasMessageContaining("entry 'java.lang.reflect'");
  }

  @Test
  public void shouldMatchTypeAtSegmentBoundary() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Runtime", ExpressionHardeningMode.ENFORCE);

    assertThat(evaluate(policy, "${bean.runtimeException.message}")).isEqualTo("boom");
  }

  @Test
  public void shouldDenyReflectionByDefault() throws Exception {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(null, ExpressionHardeningMode.ENFORCE);
    SimpleContext context = (SimpleContext) createContext(policy);
    Map<String, Object> holder = new HashMap<>();
    holder.put("method", String.class.getMethod("length"));
    holder.put("loader", ExpressionAccessPolicyTest.class.getClassLoader());
    holder.put("runtime", Runtime.getRuntime());
    holder.put("handle", java.lang.invoke.MethodHandles.lookup().unreflect(String.class.getMethod("length")));
    holder.put("lookup", java.lang.invoke.MethodHandles.publicLookup());
    holder.put("processBuilder", new ProcessBuilder("none"));
    variable(context, "holder", holder);

    // whatever getter leads to them: a Class, a Method, a ClassLoader, a Runtime, a method handle, a ProcessBuilder
    assertThatThrownBy(() -> factory.createValueExpression(context, "${bean.type}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.Class'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.method}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.reflect.AccessibleObject'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.loader}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.ClassLoader'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.runtime}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.Runtime'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${lang:import('x')}", Object.class))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'lang:import'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.handle}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.invoke.MethodHandle'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.lookup}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.invoke.MethodHandles'");
    assertThatThrownBy(() -> factory.createValueExpression(context, "${holder.processBuilder}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.ProcessBuilder'");
  }

  @Test
  public void shouldDenyTypeOfResult() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("-getClass,-class,java.lang.Class", ExpressionHardeningMode.ENFORCE);

    // the name looks harmless, the result is a Class object
    assertThatThrownBy(() -> evaluate(policy, "${bean.type}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("result of type java.lang.Class");
    assertThatThrownBy(() -> evaluate(policy, "${bean.types[0]}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyMemberOnlyOnItsType() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(Bean.class.getName() + "#delete", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.delete()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("#delete'");
    assertThat(evaluate(policy, "${other.delete()}")).isEqualTo("deleted");
  }

  @Test
  public void shouldCheckValueConvertedByResolver() {
    // a resolver that converts every value to java.lang.Runtime's class, as EL 6 lets a resolver do
    ELResolver converting = new BeanELResolver() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T convertToType(ELContext context, Object obj, Class<T> type) {
        if ("runtime".equals(obj)) {
          context.setPropertyResolved(true);
          return (T) Runtime.class;
        }
        return null;
      }
    };
    // java.lang.Runtime is a default entry; without java.lang.Class, the Class counts as the type it represents
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("-java.lang.Class", ExpressionHardeningMode.ENFORCE);
    ELResolver restricted = policy.restrict(converting);
    SimpleContext context = new SimpleContext(restricted);

    assertThatThrownBy(() -> restricted.convertToType(context, "runtime", Object.class))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.Runtime'");

    // not converted by the resolver: nothing to check
    context.setPropertyResolved(false);
    assertThat(restricted.convertToType(context, "other", Object.class)).isNull();
    assertThat(context.isPropertyResolved()).isFalse();
  }

  @Test
  public void shouldTellWhetherFunctionIsBlocked() throws Exception {
    Method method = Math.class.getMethod("abs", int.class);

    ExpressionAccessPolicy enforce = ExpressionAccessPolicy.create("fn:echo,java.lang.Math", ExpressionHardeningMode.ENFORCE);
    assertThat(enforce.blocksFunction("fn", "echo", null)).isTrue();
    assertThat(enforce.blocksFunction("math", "abs", method)).isTrue();
    assertThat(enforce.blocksFunction("out", "print", null)).isFalse();

    // report mode blocks nothing, and asking does not report
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy report = ExpressionAccessPolicy.create("fn:echo", ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);
    assertThat(report.blocksFunction("fn", "echo", null)).isFalse();
    assertThat(reports).isEmpty();
    assertThat(ExpressionAccessPolicy.create("fn:echo", ExpressionHardeningMode.OFF)
        .blocksFunction("fn", "echo", null)).isFalse();
  }

  @Test
  public void shouldDenyFunctionByName() throws Exception {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("mapper:echo,:now", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${mapper:echo('x')}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("function 'mapper:echo'");
    assertThatThrownBy(() -> evaluate(policy, "${now()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("function ':now'");
    assertThat(evaluate(policy, "${fn:echo('x')}")).isEqualTo("x");
  }

  @Test
  public void shouldDenyFunctionByDeclaringType() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(Denied.class.getName(), ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${denied:run()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("declared by " + Denied.class.getName());
  }

  @Test
  public void shouldDenySetValue() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("name", ExpressionHardeningMode.ENFORCE);
    ELContext context = createContext(policy);

    assertThatThrownBy(() -> factory.createValueExpression(context, "${bean.name}", Object.class).setValue(context, "x"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldThrowInsteadOfDecliningSoLaterResolversAreNotReached() {
    // a later resolver in the chain would resolve the property if the filter only declined
    CompositeELResolver chain = new CompositeELResolver();
    chain.add(new MapELResolver());
    chain.add(new BeanELResolver());
    ELResolver restricted = ExpressionAccessPolicy.DEFAULT.restrict(chain);
    SimpleContext context = new SimpleContext(restricted);

    assertThatThrownBy(() -> restricted.getValue(context, "", "class"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
    assertThatThrownBy(() -> restricted.invoke(context, "", "getClass", null, new Object[0]))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldReportOnceAndEvaluateInReportMode() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(REPORT_GETTER_ONLY, ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);

    assertThat(evaluate(policy, "${''.getClass().simpleName}")).isEqualTo("String");
    assertThat(evaluate(policy, "${''.getClass().simpleName}")).isEqualTo("String");

    assertThat(reports).containsExactly(
        "Expression access to 'getClass' on java.lang.String is denied by deniedExpressionAccess entry 'getClass'"
            + " in expression '${''.getClass().simpleName}'");
  }

  @Test
  public void shouldNotRestrictInOffMode() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Class", ExpressionHardeningMode.OFF);
    ELResolver chain = new BeanELResolver();

    assertThat(policy.isActive()).isFalse();
    assertThat(policy.restrict(chain)).isSameAs(chain);
    assertThat(evaluate(policy, "${''.getClass().simpleName}")).isEqualTo("String");
  }

  @Test
  public void shouldNotWrapTwice() {
    ELResolver restricted = ExpressionAccessPolicy.DEFAULT.restrict(new BeanELResolver());
    ELResolver restrictedAgain = ExpressionAccessPolicy.DEFAULT.restrict(restricted);

    assertThat(((RestrictedELResolver) restrictedAgain).getDelegate()).isInstanceOf(BeanELResolver.class);
  }

  @Test
  public void shouldParseMode() {
    assertThat(ExpressionHardeningMode.parse(" Report ")).isEqualTo(ExpressionHardeningMode.REPORT);
    assertThat(ExpressionHardeningMode.parse("off")).isEqualTo(ExpressionHardeningMode.OFF);
    assertThat(ExpressionHardeningMode.parse(null)).isEqualTo(ExpressionHardeningMode.ENFORCE);
    assertThatThrownBy(() -> ExpressionHardeningMode.parse("warn"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("off, report, enforce");
  }

  @Test
  public void shouldDenyClassObjectOfDeniedType() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(WITHOUT_DEFAULTS + ",java.lang.System", ExpressionHardeningMode.ENFORCE);

    // the target is a Class and the result a Method, neither is java.lang.System itself
    assertThatThrownBy(() -> evaluate(policy, "${''.getClass().forName('java.lang.System').getMethod('lineSeparator').invoke(null)}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.System'");
    assertThat(evaluate(policy, "${''.getClass().forName('java.lang.String').simpleName}")).isEqualTo("String");
  }

  @Test
  public void shouldDenyMemberOfDeniedType() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(Other.class.getName(), ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.otherMethod}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("result of type java.lang.reflect.Method");
  }

  @Test
  public void shouldAllowMapKeysNamedLikeDeniedEntries() {
    assertThat(evaluate(ExpressionAccessPolicy.DEFAULT, "${map['class']}")).isEqualTo("mapClass");
    assertThat(evaluate(ExpressionAccessPolicy.DEFAULT, "${map.getClass}")).isEqualTo("mapGetClass");
    assertThatThrownBy(() -> evaluate(ExpressionAccessPolicy.DEFAULT, "${map.getClass()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyMapKeyAnsweredByReflection() {
    // a chain with the bean resolver before the map resolver answers map['class'] with getClass()
    CompositeELResolver chain = new CompositeELResolver();
    chain.add(new BeanELResolver());
    chain.add(new MapELResolver());
    // without java.lang.Class, which would deny the result anyway
    ELResolver restricted = ExpressionAccessPolicy.create("-java.lang.Class", ExpressionHardeningMode.ENFORCE).restrict(chain);

    assertThatThrownBy(() -> restricted.getValue(new SimpleContext(restricted), new HashMap<String, Object>(), "class"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'class'");
  }

  @Test
  public void shouldAlwaysDenyElApiAndFilter() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(WITHOUT_DEFAULTS, ExpressionHardeningMode.ENFORCE);

    assertThat(policy.getDeniedEntries()).isEmpty();
    assertThat(policy.isActive()).isTrue();
    // the unrestricted chain behind the filter
    assertThatThrownBy(() -> evaluate(policy, "${bean.resolver}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'org.cibseven.bpm.impl.juel'");
    assertThatThrownBy(() -> evaluate(policy, "${bean.elContext}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("result of type " + ProcessEngineElContext.class.getName())
        .hasMessageContaining("entry 'jakarta.el'");
    assertThatThrownBy(() -> evaluate(policy, "${bean.policy}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
    assertThatThrownBy(() -> evaluate(policy, "${''.getClass().forName('jakarta.el.ELContext')}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldAcceptWildcardEntries() {
    assertThat(ExpressionAccessPolicy.create("java.lang.reflect.*,get*,com.customer.Admin#*,lang:*", null).getDeniedEntries())
        .contains("java.lang.reflect.*", "get*", "com.customer.Admin#*", "lang:*");
  }

  @Test
  public void shouldRejectInvalidEntries() {
    for (String entry : new String[] { "#delete", "com.customer.Admin#", "a:b#c", "a#b#c", "lang:", "a:b:c",
        "java..lang", ".java", "java.", "get Class", "-unknown", "-GetClass", "foo,-foo", "getClass,-getClass",
        "getClass()", "exec(", "*", "*.*", ":*", "*#*", "com.customer.Admin#b.c", "lang:a.b" }) {
      assertThatThrownBy(() -> ExpressionAccessPolicy.create(entry, ExpressionHardeningMode.ENFORCE))
          .as(entry)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("Invalid deniedExpressionAccess entry");
    }
  }

  @Test
  public void shouldReportOncePerEntryAndTypeWithSanitizedName() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("exec*", ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);

    // names from data: the property does not exist, the access is reported before it fails
    for (String name : new String[] { "exec\nINFO forged", "exec2" }) {
      ELContext context = createContext(policy);
      context.getVariableMapper().setVariable("name", factory.createValueExpression(name, Object.class));
      assertThatThrownBy(() -> factory.createValueExpression(context, "${bean[name]}", Object.class).getValue(context))
          .isInstanceOf(PropertyNotFoundException.class);
    }

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0)).doesNotContain("\n").contains("'exec?INFO forged'");
  }

  @Test
  public void shouldSanitizeUnicodeLineSeparatorsAndBidiOverrides() {
    // U+2028 line separator, U+2029 paragraph separator, U+202E right-to-left override
    for (String name : new String[] { "exec INFO forged", "exec INFO forged", "exec‮degrof" }) {
      List<String> reports = new ArrayList<>();
      ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("exec*", ExpressionHardeningMode.REPORT)
          .withReporter(reports::add);
      ELContext context = createContext(policy);
      context.getVariableMapper().setVariable("name", factory.createValueExpression(name, Object.class));
      assertThatThrownBy(() -> factory.createValueExpression(context, "${bean[name]}", Object.class).getValue(context))
          .isInstanceOf(PropertyNotFoundException.class);

      assertThat(reports).hasSize(1);
      assertThat(reports.get(0)).as(name).doesNotContain(" ", " ", "‮").contains("'exec?");
    }
  }

  @Test
  public void shouldApplyPolicySetBeforeUse() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF));

    assertThat(evaluate(expressionManager, "${''.getClass().simpleName}")).isEqualTo("String");
  }

  @Test
  public void shouldNotChangePolicyAfterUse() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    assertThatThrownBy(() -> evaluate(expressionManager, "${''.getClass().simpleName}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);

    assertThatThrownBy(() -> expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF)))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("cannot be changed after the expression manager was used");
    // setting the same policy again is no change
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.DEFAULT);
    assertThatThrownBy(() -> evaluate(expressionManager, "${''.getClass().simpleName}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyPropertyByWildcardGetterEntry() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("get*Services", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.processEngineServices}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'get*Services'");
  }

  @Test
  public void shouldDenyPropertyByMemberGetterEntry() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(Bean.class.getName() + "#getName", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.name}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("#getName'");
    assertThat(evaluate(policy, "${denied.name}")).isEqualTo("denied");
  }

  @Test
  public void shouldDenyPropertyByIsGetterEntry() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("isActive", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${bean.active}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'isActive'");
  }

  @Test
  public void shouldDenyFunctionDeclaredByProtectedType() throws Exception {
    // e.g. imported with lang:import in a JUEL script
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(WITHOUT_DEFAULTS, ExpressionHardeningMode.ENFORCE);
    ELContext context = createContext(policy);
    ((SimpleContext) context).setFunction("p", "create",
        ExpressionAccessPolicy.class.getMethod("create", String.class, ExpressionHardeningMode.class));

    assertThatThrownBy(() -> factory.createValueExpression(context, "${p:create('', null)}", Object.class))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("declared by " + ExpressionAccessPolicy.class.getName());
  }

  @Test
  public void shouldAllowFunctionOfFunctionMapperSubclass() {
    // FEEL declares its built-in functions in a FunctionMapper subclass, a subtype of a protected type
    assertThat(evaluate(ExpressionAccessPolicy.DEFAULT, "${mapper:echo('x')}")).isEqualTo("x");
  }

  @Test
  public void shouldDenyFunctionReturningDeniedType() throws Exception {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Class", ExpressionHardeningMode.ENFORCE);
    ELContext context = createContext(policy);
    // declared by an allowed type, returns a denied one
    ((SimpleContext) context).setFunction("c", "typeOf", method(ExpressionAccessPolicyTest.class, "typeOf"));

    assertThatThrownBy(() -> factory.createValueExpression(context, "${c:typeOf('x')}", Object.class))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("returns java.lang.Class");
  }

  @Test
  public void shouldKeepStricterRestriction() {
    ELResolver strict = ExpressionAccessPolicy.DEFAULT.restrict(new BeanELResolver());
    ExpressionAccessPolicy lenient = ExpressionAccessPolicy.create(WITHOUT_DEFAULTS, ExpressionHardeningMode.ENFORCE);
    ExpressionAccessPolicy off = ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF);
    SimpleContext context = new SimpleContext();

    ELResolver both = lenient.restrict(strict);
    assertThatThrownBy(() -> both.invoke(context, "", "getClass", null, new Object[0]))
        .isInstanceOf(ExpressionAccessDeniedException.class);
    assertThat(off.restrict(strict)).isSameAs(strict);
  }

  @Test
  public void shouldReportOncePerExpression() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(REPORT_GETTER_ONLY, ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);

    evaluate(policy, "${''.getClass().simpleName}");
    evaluate(policy, "${''.getClass().simpleName}");
    evaluate(policy, "${'abc'.getClass().name}");

    assertThat(reports).hasSize(2);
    assertThat(reports.get(1)).endsWith(" in expression '${'abc'.getClass().name}'");
  }

  @Test
  public void shouldNameOuterExpressionAfterNestedEvaluation() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(REPORT_GETTER_ONLY, ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);
    ELContext context = createContext(policy);
    ((SimpleContext) context).setFunction("fn", "inner", method(ExpressionAccessPolicyTest.class, "evaluateInner"));
    NESTED.set(() -> factory.createValueExpression(context, "${'inner'.length()}", Object.class).getValue(context));
    try {
      // the function evaluates an inner expression before the outer one hits its violation
      factory.createValueExpression(context, "${fn:inner().getClass().name}", Object.class).getValue(context);
      factory.createValueExpression(context, "${fn:inner().getClass().simpleName}", Object.class).getValue(context);
    } finally {
      NESTED.remove();
    }

    // reported for each outer expression, named in each report
    assertThat(reports).hasSize(2);
    assertThat(reports.get(0)).endsWith(" in expression '${fn:inner().getClass().name}'");
    assertThat(reports.get(1)).endsWith(" in expression '${fn:inner().getClass().simpleName}'");
  }

  private static final ThreadLocal<java.util.function.Supplier<Object>> NESTED = new ThreadLocal<>();

  public static Object evaluateInner() {
    return NESTED.get().get();
  }

  @Test
  public void shouldStartReportWindowOverWhenFull() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(REPORT_GETTER_ONLY, ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);
    ELContext context = createContext(policy);

    for (int i = 0; i <= 10_000; i++) {
      factory.createValueExpression(context, "${''.getClass().simpleName == '" + i + "'}", Object.class).getValue(context);
    }
    factory.createValueExpression(context, "${''.getClass().simpleName == '0'}", Object.class).getValue(context);

    // the set is bounded: after 10,000 distinct reports it starts over, the first one is reported again
    assertThat(reports).hasSize(10_002);
  }

  @Test
  public void shouldKeepClassPropertyDeniedWhenOnlyClassIsRemoved() {
    // the property class is read by getClass(), which stays denied
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("-class", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${''.class}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'getClass'");
    assertThatThrownBy(() -> evaluate(policy, "${''.getClass()}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyRuntimeResultOfFunction() {
    // declared to return Object, so the declared return type does not show it
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Class", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${fn:anything('x')}"))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("result of type java.lang.Class");
    assertThat(evaluate(ExpressionAccessPolicy.DEFAULT, "${fn:echo('x')}")).isEqualTo("x");
  }

  @Test
  public void shouldCheckJdkProxyByItsInterfaces() {
    // e.g. a Spring AOP bean: a subclass of java.lang.reflect.Proxy
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.reflect", ExpressionHardeningMode.ENFORCE);

    assertThat(evaluate(policy, "${proxy.name}")).isEqualTo("proxy");
    ExpressionAccessPolicy interfacePolicy = ExpressionAccessPolicy.create(Named.class.getName(), ExpressionHardeningMode.ENFORCE);
    assertThatThrownBy(() -> evaluate(interfacePolicy, "${proxy.name}"))
        .isInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldReportDeniedFunctionOncePerExpression() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("lang:import", ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);
    ELContext context = createContext(policy);

    // functions are checked when the expression is parsed
    factory.createValueExpression(context, "${lang:import('a')}", Object.class);
    factory.createValueExpression(context, "${lang:import('b')}", Object.class);

    assertThat(reports).hasSize(2);
    assertThat(reports.get(0)).endsWith(" in expression '${lang:import('a')}'");
    assertThat(reports.get(1)).endsWith(" in expression '${lang:import('b')}'");
  }

  @Test
  public void shouldPutExpressionInEnforceMessageOfDeniedFunction() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("lang:import", ExpressionHardeningMode.ENFORCE);

    assertThatThrownBy(() -> evaluate(policy, "${lang:import('x')}"))
        .hasMessageEndingWith(" in expression '${lang:import('x')}'");
  }

  @Test
  public void shouldDenyFunctionResultInWrappedContext() {
    // the context wraps the restricted resolver, so the function mapper's policy checks the result
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Class", ExpressionHardeningMode.ENFORCE);
    ELContext restricted = createContext(policy);
    CompositeELResolver wrapper = new CompositeELResolver();
    wrapper.add(restricted.getELResolver());
    ELContext context = new ELContext() {
      @Override
      public ELResolver getELResolver() {
        return wrapper;
      }

      @Override
      public FunctionMapper getFunctionMapper() {
        return restricted.getFunctionMapper();
      }

      @Override
      public jakarta.el.VariableMapper getVariableMapper() {
        return restricted.getVariableMapper();
      }
    };

    assertThatThrownBy(() -> factory.createValueExpression(context, "${fn:anything('x')}", Object.class).getValue(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("result of type java.lang.Class");
  }

  @Test
  public void shouldHandlePropertyWithoutName() {
    ELContext context = createContext(ExpressionAccessPolicy.DEFAULT);
    context.getVariableMapper().setVariable("nameless", factory.createValueExpression(new Object() {
      @Override
      public String toString() {
        return null;
      }
    }, Object.class));

    // the bean has no property "null": a clean EL error, no NullPointerException
    assertThatThrownBy(() -> factory.createValueExpression(context, "${bean[nameless]}", Object.class).getValue(context))
        .isInstanceOf(jakarta.el.ELException.class);
  }

  @Test
  public void shouldPutExpressionInMessageOfValueReference() {
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create("java.lang.Class", ExpressionHardeningMode.ENFORCE);
    ELContext context = createContext(policy);

    assertThatThrownBy(() -> factory.createValueExpression(context, "${bean.type.name}", Object.class).getValueReference(context))
        .isInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageEndingWith(" in expression '${bean.type.name}'");
  }

  @Test
  public void shouldHaveDefaultReporterOnDefaultPolicy() {
    // DEFAULT is built after the static fields it uses
    assertThat(ExpressionAccessPolicy.DEFAULT.hasDefaultReporter()).isTrue();
    assertThat(ExpressionAccessPolicy.create(null, null).hasDefaultReporter()).isTrue();
  }

  @Test
  public void shouldPutExpressionInEnforceMessage() {
    assertThatThrownBy(() -> evaluate(ExpressionAccessPolicy.DEFAULT, "${''.class}"))
        .hasMessageEndingWith(" in expression '${''.class}'");
  }

  protected Object evaluate(JuelExpressionManager expressionManager, String expression) {
    ELContext context = expressionManager.createElContext(Variables.createVariables().asVariableContext());
    return expressionManager.createValueExpression(expression).getValue(context);
  }

  protected Object evaluate(ExpressionAccessPolicy policy, String expression) {
    ELContext context = createContext(policy);
    return factory.createValueExpression(context, expression, Object.class).getValue(context);
  }

  protected ELContext createContext(ExpressionAccessPolicy policy) {
    CompositeELResolver chain = new CompositeELResolver();
    chain.add(new MapELResolver());
    chain.add(new jakarta.el.ArrayELResolver());
    chain.add(new BeanELResolver());
    ELResolver resolver = policy.restrict(chain);
    SimpleContext context = new SimpleContext(resolver) {
      final FunctionMapper functions = policy.restrict(super.getFunctionMapper());

      @Override
      public FunctionMapper getFunctionMapper() {
        return functions;
      }
    };
    context.setFunction("lang", "import", method(ExpressionAccessPolicyTest.class, "echo"));
    context.setFunction("", "now", method(ExpressionAccessPolicyTest.class, "echo"));
    context.setFunction("fn", "echo", method(ExpressionAccessPolicyTest.class, "echo"));
    context.setFunction("denied", "run", method(Denied.class, "run"));
    context.setFunction("mapper", "echo", method(EchoFunctionMapper.class, "echo"));
    context.setFunction("fn", "anything", method(ExpressionAccessPolicyTest.class, "anything"));
    variable(context, "proxy", java.lang.reflect.Proxy.newProxyInstance(Named.class.getClassLoader(),
        new Class<?>[] { Named.class }, (instance, invoked, arguments) -> "proxy"));

    Map<String, Object> map = new HashMap<>();
    map.put("key", "value");
    map.put("processEngineServices", "mapValue");
    map.put("class", "mapClass");
    map.put("getClass", "mapGetClass");
    variable(context, "map", map);
    variable(context, "bean", new Bean("bean"));
    variable(context, "other", new Other());
    variable(context, "denied", new Denied());
    variable(context, "property", "class");
    variable(context, "method", "getClass");
    return context;
  }

  protected void variable(SimpleContext context, String name, Object value) {
    context.setVariable(name, factory.createValueExpression(value, Object.class));
  }

  protected static Method method(Class<?> type, String name) {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name)) {
        return method;
      }
    }
    throw new IllegalArgumentException(name);
  }

  public static String echo(String value) {
    return value;
  }

  public static Class<?> typeOf(Object value) {
    return value.getClass();
  }

  public static Object anything(Object value) {
    return value.getClass();
  }

  public interface Named {
    String getName();
  }

  public static class Bean {

    protected final String name;

    public Bean(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
    }

    public String getExecution() {
      return "execution";
    }

    public String exec() {
      return "exec";
    }

    public Object getProcessEngineServices() {
      return "services";
    }

    public Class<?> getType() {
      return String.class;
    }

    public Class<?>[] getTypes() {
      return new Class<?>[] { String.class };
    }

    public Method getMethod() {
      return method(Bean.class, "getName");
    }

    public boolean isActive() {
      return true;
    }

    public Method getOtherMethod() {
      return method(Other.class, "delete");
    }

    public ELResolver getResolver() {
      return ExpressionAccessPolicy.DEFAULT.restrict(new BeanELResolver());
    }

    public ELContext getElContext() {
      return new ProcessEngineElContext(null);
    }

    public ExpressionAccessPolicy getPolicy() {
      return ExpressionAccessPolicy.DEFAULT;
    }

    public RuntimeException getRuntimeException() {
      return new IllegalStateException("boom");
    }

    public String delete() {
      return "deleted";
    }

    @Override
    public String toString() {
      return "Bean";
    }
  }

  public static class EchoFunctionMapper extends FunctionMapper {

    public static String echo(String value) {
      return value;
    }

    @Override
    public Method resolveFunction(String prefix, String localName) {
      return null;
    }
  }

  public static class Other {

    public String delete() {
      return "deleted";
    }
  }

  public static class DeniedBase implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public String getName() {
      return "denied";
    }
  }

  public static class Denied extends DeniedBase {

    private static final long serialVersionUID = 1L;

    public static String run() {
      return Collections.emptyList().toString();
    }
  }
}
