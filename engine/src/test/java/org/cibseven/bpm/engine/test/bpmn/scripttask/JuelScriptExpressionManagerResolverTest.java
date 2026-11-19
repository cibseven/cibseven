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
package org.cibseven.bpm.engine.test.bpmn.scripttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ListResourceBundle;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.cibseven.bpm.dmn.engine.impl.spi.el.ElProvider;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.el.ElProviderCompatible;
import org.cibseven.bpm.engine.impl.el.Expression;
import org.cibseven.bpm.engine.impl.el.ExpressionManager;
import org.cibseven.bpm.engine.impl.el.JuelExpressionManager;
import org.cibseven.bpm.engine.impl.el.StartProcessVariableScope;
import org.cibseven.bpm.engine.impl.el.VariableContextElResolver;
import org.cibseven.bpm.engine.impl.el.VariableScopeElResolver;
import org.cibseven.bpm.engine.impl.scripting.ExecutableScript;
import org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngineFactory;
import org.cibseven.bpm.engine.impl.util.ScriptUtil;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.test.mock.MockExpressionManager;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.cibseven.commons.testing.WatchLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.el.ArrayELResolver;
import jakarta.el.CompositeELResolver;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.ListELResolver;
import jakarta.el.MapELResolver;
import jakarta.el.MethodNotFoundException;
import jakarta.el.PropertyNotFoundException;

/**
 * JUEL scripts use the resolver chain of the configured expression manager.
 */
public class JuelScriptExpressionManagerResolverTest {

  protected static final String BLOCKED = "blocked by restricting resolver";

  protected static final String REFLECTION_PAYLOAD =
      "#{''.getClass().forName('java.lang.System').getMethod('getProperty', ''.getClass()).invoke(null, 'java.version')}";

  // closing brace left open
  protected static final String ENGINE_API_ACCESS =
      "${execution.processEngineServices.runtimeService.createProcessInstanceQuery().count()";

  // the last two read the member name from a variable
  static Stream<String> accessNotations() {
    return Stream.of(
        "#{''.getClass()}",
        "#{''.class}",
        "#{''['class']}",
        "#{''['getClass']()}",
        "#{''[property]}",
        "#{''[method]()}");
  }

  @RegisterExtension
  public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule();

  protected ProcessEngine engine;

  @AfterEach
  public void closeEngine() {
    if (engine != null) {
      for (Deployment deployment : engine.getRepositoryService().createDeploymentQuery().list()) {
        engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
      }
      engine.close();
      engine = null;
    }
  }

  @ParameterizedTest
  @MethodSource("accessNotations")
  public void shouldApplyExpressionManagerRestrictionToGatewayCondition(String notation) {
    // given
    buildEngine(new RestrictingExpressionManager());
    deploy(Bpmn.createExecutableProcess("testProcess")
        .startEvent()
        .exclusiveGateway()
          .condition("payload", notation)
          .endEvent()
        .moveToLastGateway()
          .condition("otherwise", "${true}")
          .endEvent()
        .done());

    // when / then
    assertThatThrownBy(() -> startProcess(memberNameVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @ParameterizedTest
  @MethodSource("accessNotations")
  public void shouldApplyExpressionManagerRestrictionToJuelScript(String notation) {
    // given
    buildEngine(new RestrictingExpressionManager());
    deploy(juelScriptProcess(notation));

    // when / then
    assertThatThrownBy(() -> startProcess(memberNameVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldApplyExpressionManagerRestrictionToReflectionPayload() {
    // given
    buildEngine(new RestrictingExpressionManager());
    deploy(juelScriptProcess(REFLECTION_PAYLOAD));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldApplyExpressionManagerRestrictionToExecutionListenerScript() {
    // given
    buildEngine(new RestrictingExpressionManager());
    deployXml(processXml(
        "<extensionElements>"
        + "<camunda:executionListener event=\"start\">"
        + "<camunda:script scriptFormat=\"juel\">#{''.getClass()}</camunda:script>"
        + "</camunda:executionListener>"
        + "</extensionElements>",
        "", "<sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"task\" />"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldApplyExpressionManagerRestrictionToScriptCondition() {
    // given
    buildEngine(new RestrictingExpressionManager());
    deployXml(processXml("", "",
        "<sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"task\">"
        + "<conditionExpression xsi:type=\"tFormalExpression\" language=\"juel\">#{''.getClass() != null}</conditionExpression>"
        + "</sequenceFlow>"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldApplyExpressionManagerRestrictionToInputMappingScript() {
    // given
    buildEngine(new RestrictingExpressionManager());
    deployXml(processXml("",
        "<extensionElements>"
        + "<camunda:inputOutput>"
        + "<camunda:inputParameter name=\"input\">"
        + "<camunda:script scriptFormat=\"juel\">#{''.getClass()}</camunda:script>"
        + "</camunda:inputParameter>"
        + "</camunda:inputOutput>"
        + "</extensionElements>",
        "<sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"task\" />"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldNotFallBackToBeanResolverWhenChainHasNone() {
    // given
    buildEngine(new NoBeanResolverExpressionManager());
    deploy(juelScriptProcess(REFLECTION_PAYLOAD));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(MethodNotFoundException.class);
  }

  @Test
  public void shouldEvaluateUnrestrictedJuelScriptWithRestrictingExpressionManager() {
    // given
    buildEngine(new RestrictingExpressionManager());

    // then
    assertThat(evaluate("${name.toUpperCase()}", "name", "abc")).isEqualTo("ABC");
  }

  @ParameterizedTest(name = "customized expression manager: {0}")
  @ValueSource(booleans = {false, true})
  public void shouldKeepJuelScriptNavigation(boolean customized) {
    // given
    buildEngine(expressionManager(customized));
    Map<String, Object> map = new HashMap<>();
    map.put("key", "mapValue");

    // then
    assertThat(evaluate("${number + 1}", "number", 41)).isEqualTo(42L);
    assertThat(evaluate("${name.toUpperCase()}", "name", "abc")).isEqualTo("ABC");
    assertThat(evaluate("${name.length()}", "name", "abc")).isEqualTo(3);
    assertThat(evaluate("${name.split(',')[1]}", "name", "a,b,c")).isEqualTo("b");
    assertThat(evaluate("${list[1]}", "list", Arrays.asList("first", "second"))).isEqualTo("second");
    assertThat(evaluate("${list.size()}", "list", Arrays.asList("first", "second"))).isEqualTo(2);
    assertThat(evaluate("${map.key}", "map", map)).isEqualTo("mapValue");
    assertThat(evaluate("${map['key']}", "map", map)).isEqualTo("mapValue");
    assertThat(evaluate("${execution.getVariable('name')}", "name", "abc")).isEqualTo("abc");
    assertThat(evaluate("${execution.processInstance.id == execution.processInstanceId}", "name", "abc")).isEqualTo(true);
  }

  @ParameterizedTest(name = "customized expression manager: {0}")
  @ValueSource(booleans = {false, true})
  public void shouldResolveResourceBundleKeyInJuelScript(boolean customized) {
    // given
    buildEngine(expressionManager(customized), configuration -> configuration.setJavaSerializationFormatEnabled(true));

    // then
    assertThat(evaluate("${bundle.greeting}", "bundle", Variables.objectValue(new GreetingBundle(), true).create()))
        .isEqualTo("hello");
  }

  @Test
  public void shouldFailOnUnknownVariableInJuelScript() {
    // given
    buildEngine(new CustomJuelExpressionManager());
    deploy(juelScriptProcess("${unknown}"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(PropertyNotFoundException.class)
        .hasStackTraceContaining("Cannot resolve identifier 'unknown'");
  }

  @Test
  public void shouldKeepUnknownVariableMessageWithStockExpressionManager() {
    // given
    buildEngine(null);
    deploy(juelScriptProcess("${unknown}"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(PropertyNotFoundException.class)
        .hasStackTraceContaining("Cannot find property unknown");
  }

  @Test
  public void shouldKeepScriptContextWithStockExpressionManager() {
    // given
    buildEngine(null);

    // then
    assertThat(evaluate("${context != null}", "name", "abc")).isEqualTo(true);
  }

  @Test
  public void shouldKeepBeanBeforeVariableWithStockExpressionManager() {
    // given a configuration bean and a variable with the same name
    Map<Object, Object> beans = new HashMap<>();
    beans.put("name", "beanValue");
    buildEngine(null, configuration -> configuration.setBeans(beans));

    // then the bean wins, as before
    assertThat(evaluate("${name}", "name", "variableValue")).isEqualTo("beanValue");
  }

  @Test
  public void shouldNotApplyStockExpressionManagersToJuelScripts() {
    assertThat(new JuelExpressionManager().isAppliedToJuelScripts()).isFalse();
    assertThat(new MockExpressionManager().isAppliedToJuelScripts()).isFalse();
    assertThat(new CustomJuelExpressionManager().isAppliedToJuelScripts()).isTrue();
    assertThat(new MockExpressionManager() { }.isAppliedToJuelScripts()).isTrue();
  }

  @Test
  public void shouldApplyEntryNameRestrictionToGatewayCondition() {
    // given
    buildEngine(new EntryNameRestrictingExpressionManager("execution"));
    deploy(Bpmn.createExecutableProcess("testProcess")
        .startEvent()
        .exclusiveGateway()
          .condition("payload", ENGINE_API_ACCESS + " >= 0}")
          .endEvent()
        .moveToLastGateway()
          .condition("otherwise", "${true}")
          .endEvent()
        .done());

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldApplyEntryNameRestrictionToJuelScript() {
    // given
    buildEngine(new EntryNameRestrictingExpressionManager("execution"));
    deploy(juelScriptProcess(ENGINE_API_ACCESS + "}"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @ParameterizedTest(name = "customized expression manager: {0}")
  @ValueSource(booleans = {false, true})
  public void shouldResolveVariableScopeKeyInJuelScript(boolean customized) {
    // given
    buildEngine(expressionManager(customized));

    // then
    assertThat(evaluateWithScope("${scope.variableScopeKey}", StartProcessVariableScope.getSharedInstance()))
        .isEqualTo("scope");
  }

  @Test
  public void shouldApplyEntryNameRestrictionToVariableScopeKey() {
    // given
    buildEngine(new EntryNameRestrictingExpressionManager("scope"));

    // when / then
    assertThatThrownBy(() -> evaluateWithScope("${scope.variableScopeKey}", StartProcessVariableScope.getSharedInstance()))
        .isInstanceOf(ProcessEngineException.class)
        .hasStackTraceContaining(BLOCKED);
  }

  @Test
  public void shouldNotExposeScriptContextInJuelScript() {
    // given
    buildEngine(new EntryNameRestrictingExpressionManager("execution"));
    deploy(juelScriptProcess("${context.getAttribute('execution').processInstanceId}"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(PropertyNotFoundException.class)
        .hasStackTraceContaining("'context'");
  }

  @Test
  public void shouldResolveBeansOfTheExpressionManagerInJuelScript() {
    // given
    Map<Object, Object> beans = new HashMap<>();
    beans.put("someBean", "beanValue");
    buildEngine(new CustomJuelExpressionManager(beans));

    // then
    assertThat(evaluate("${someBean}", "name", "abc")).isEqualTo("beanValue");
  }

  @Test
  public void shouldNotResolveBeansHiddenByTheExpressionManagerInJuelScript() {
    // given a bean the expression manager does not expose
    Map<Object, Object> beans = new HashMap<>();
    beans.put("someBean", "beanValue");
    buildEngine(new CustomJuelExpressionManager(), configuration -> configuration.setBeans(beans));
    deploy(juelScriptProcess("${someBean}"));

    // when / then
    assertThatThrownBy(() -> startProcess(Variables.createVariables()))
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(PropertyNotFoundException.class)
        .hasStackTraceContaining("'someBean'");
  }

  @Test
  public void shouldPreferVariableOverBeanInJuelScript() {
    // given
    Map<Object, Object> beans = new HashMap<>();
    beans.put("name", "beanValue");
    buildEngine(new CustomJuelExpressionManager(beans));

    // then
    assertThat(evaluate("${name}", "name", "variableValue")).isEqualTo("variableValue");
  }

  @ParameterizedTest(name = "customized expression manager: {0}")
  @ValueSource(booleans = {false, true})
  public void shouldResolveTaskInTaskListenerScript(boolean customized) {
    // given
    buildEngine(expressionManager(customized));
    deployXml(processXml("",
        "<extensionElements>"
        + "<camunda:taskListener event=\"create\">"
        + "<camunda:script scriptFormat=\"juel\">${task.setVariable('key', task.taskDefinitionKey)}</camunda:script>"
        + "</camunda:taskListener>"
        + "</extensionElements>",
        "<sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"task\" />"));

    // when
    ProcessInstance processInstance = startProcess(Variables.createVariables());

    // then
    assertThat(engine.getRuntimeService().getVariable(processInstance.getId(), "key")).isEqualTo("task");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.script"}, level = "WARN")
  public void shouldUseDefaultResolversAndWarnForOtherExpressionManager() {
    // given
    buildEngine(new DelegatingExpressionManager(new RestrictingExpressionManager()));

    // when
    Object result = evaluate("#{''.getClass().simpleName}", "name", "abc");
    evaluate("${name}", "name", "abc");

    // then
    assertThat(result).isEqualTo("String");
    assertThat(loggingRule.getFilteredLog(DelegatingExpressionManager.class.getName()
        + "' is not a JuelExpressionManager")).hasSize(1);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.script"}, level = "WARN")
  public void shouldWarnWithoutScriptBindings() {
    // given a customized expression manager and bindings that are not ScriptBindings
    buildEngine(new RestrictingExpressionManager());
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

    // when two scripts are evaluated inside a command
    Object result = configuration.getCommandExecutorTxRequired().execute(commandContext -> {
      ScriptEngine scriptEngine = new JuelScriptEngineFactory().getScriptEngine();
      SimpleBindings bindings = new SimpleBindings();
      bindings.put("name", "abc");
      try {
        scriptEngine.eval("${name}", bindings);
        return scriptEngine.eval("${name.toUpperCase()}", new SimpleBindings(bindings));
      } catch (ScriptException e) {
        throw new IllegalStateException(e);
      }
    });

    // then the previous resolution is used and the warning is logged once
    assertThat(result).isEqualTo("ABC");
    assertThat(loggingRule.getFilteredLog("JUEL script evaluated without the engine's script bindings")).hasSize(1);
  }

  @Test
  public void shouldUseDefaultResolversWithoutCommandContext() throws Exception {
    // given no command context
    ScriptEngine scriptEngine = new JuelScriptEngineFactory().getScriptEngine();
    scriptEngine.put("name", "abc");

    // then
    assertThat(scriptEngine.eval("${name.toUpperCase()}")).isEqualTo("ABC");
    assertThat(scriptEngine.eval("${name.split(',')[0]}")).isEqualTo("abc");
  }

  protected VariableMap memberNameVariables() {
    return Variables.createVariables()
        .putValue("property", "class")
        .putValue("method", "getClass");
  }

  protected Object evaluate(String script, String variableName, Object value) {
    deploy(juelScriptProcess(script));
    ProcessInstance processInstance = startProcess(Variables.createVariables().putValue(variableName, value));
    return engine.getRuntimeService().getVariable(processInstance.getId(), "result");
  }

  protected Object evaluateWithScope(String source, VariableScope variableScope) {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    return configuration.getCommandExecutorTxRequired().execute(commandContext -> {
      ExecutableScript script = ScriptUtil.getScriptFromSource("juel", source, configuration.getScriptFactory());
      return configuration.getScriptingEnvironment().execute(script, variableScope);
    });
  }

  protected ProcessInstance startProcess(VariableMap variables) {
    return engine.getRuntimeService().startProcessInstanceByKey("testProcess", variables);
  }

  protected BpmnModelInstance juelScriptProcess(String script) {
    return Bpmn.createExecutableProcess("testProcess")
        .startEvent()
        .scriptTask()
          .scriptFormat("juel")
          .scriptText(script)
          .camundaResultVariable("result")
        .userTask()
        .endEvent()
        .done();
  }

  /**
   * Start event, flow and user task, each with the given XML.
   */
  protected String processXml(String startEventContent, String taskContent, String flow) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
        + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\""
        + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
        + " targetNamespace=\"test\">"
        + "<process id=\"testProcess\" isExecutable=\"true\">"
        + "<startEvent id=\"start\">" + startEventContent + "</startEvent>"
        + flow
        + "<userTask id=\"task\">" + taskContent + "</userTask>"
        + "</process>"
        + "</definitions>";
  }

  protected void deploy(BpmnModelInstance process) {
    engine.getRepositoryService().createDeployment()
        .addModelInstance("testProcess.bpmn", process)
        .deploy();
  }

  protected void deployXml(String xml) {
    engine.getRepositoryService().createDeployment()
        .addString("testProcess.bpmn", xml)
        .deploy();
  }

  protected ExpressionManager expressionManager(boolean customized) {
    return customized ? new CustomJuelExpressionManager() : null;
  }

  protected void buildEngine(ExpressionManager expressionManager) {
    buildEngine(expressionManager, configuration -> { });
  }

  protected void buildEngine(ExpressionManager expressionManager, Consumer<ProcessEngineConfigurationImpl> configurator) {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    configuration.setProcessEngineName(getClass().getSimpleName());
    if (expressionManager != null) {
      configuration.setExpressionManager(expressionManager);
    }
    configurator.accept(configuration);
    engine = configuration.buildProcessEngine();
  }

  /**
   * Customized expression manager without restrictions.
   */
  public static class CustomJuelExpressionManager extends JuelExpressionManager {

    public CustomJuelExpressionManager() {
    }

    public CustomJuelExpressionManager(Map<Object, Object> beans) {
      super(beans);
    }
  }

  /**
   * Hardening as an operator writes it: a throwing resolver in front of the chain.
   */
  public static class RestrictingExpressionManager extends JuelExpressionManager {

    @Override
    protected ELResolver createElResolver() {
      CompositeELResolver resolver = new CompositeELResolver();
      resolver.add(new RestrictingElResolver());
      resolver.add(super.createElResolver());
      return resolver;
    }
  }

  /**
   * Blocks only the given top-level name.
   */
  public static class EntryNameRestrictingExpressionManager extends JuelExpressionManager {

    protected final String blockedName;

    public EntryNameRestrictingExpressionManager(String blockedName) {
      this.blockedName = blockedName;
    }

    @Override
    protected ELResolver createElResolver() {
      CompositeELResolver resolver = new CompositeELResolver();
      resolver.add(new EntryNameRestrictingElResolver(blockedName));
      resolver.add(super.createElResolver());
      return resolver;
    }
  }

  public static class EntryNameRestrictingElResolver extends RestrictingElResolver {

    protected final String blockedName;

    public EntryNameRestrictingElResolver(String blockedName) {
      this.blockedName = blockedName;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
      if (base == null && blockedName.equals(property)) {
        throw new ELException(BLOCKED + ": " + property);
      }
      return null;
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] paramTypes, Object[] params) {
      return null;
    }
  }

  /**
   * Allow list style: no bean resolver.
   */
  public static class NoBeanResolverExpressionManager extends JuelExpressionManager {

    @Override
    protected ELResolver createElResolver() {
      CompositeELResolver resolver = new CompositeELResolver();
      resolver.add(new VariableScopeElResolver());
      resolver.add(new VariableContextElResolver());
      resolver.add(new ArrayELResolver());
      resolver.add(new ListELResolver());
      resolver.add(new MapELResolver());
      return resolver;
    }
  }

  public static class DelegatingExpressionManager implements ExpressionManager, ElProviderCompatible {

    protected final JuelExpressionManager delegate;

    public DelegatingExpressionManager(JuelExpressionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public ElProvider toElProvider() {
      return delegate.toElProvider();
    }

    @Override
    public Expression createExpression(String expression) {
      return delegate.createExpression(expression);
    }

    @Override
    public void addFunction(String name, Method function) {
      delegate.addFunction(name, function);
    }
  }

  public static class GreetingBundle extends ListResourceBundle implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    protected Object[][] getContents() {
      return new Object[][] { { "greeting", "hello" } };
    }
  }

  public static class RestrictingElResolver extends ELResolver {

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
      if (base != null && "class".equals(property)) {
        throw new ELException(BLOCKED + ": " + property);
      }
      return null;
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] paramTypes, Object[] params) {
      if (base != null && "getClass".equals(method)) {
        throw new ELException(BLOCKED + ": " + method);
      }
      return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
      return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
      return false;
    }


    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
      return null;
    }
  }
}
