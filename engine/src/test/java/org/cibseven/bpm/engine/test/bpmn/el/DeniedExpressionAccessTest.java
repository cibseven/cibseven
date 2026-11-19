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
package org.cibseven.bpm.engine.test.bpmn.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.cibseven.bpm.dmn.engine.impl.spi.el.ElProvider;
import org.cibseven.bpm.dmn.feel.impl.juel.FeelEngineFactoryImpl;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.el.ElProviderCompatible;
import org.cibseven.bpm.engine.impl.el.Expression;
import org.cibseven.bpm.engine.impl.el.ExpressionManager;
import org.cibseven.bpm.engine.impl.el.JuelExpressionManager;
import org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngine;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.impl.juel.ExpressionAccessDeniedException;
import org.cibseven.bpm.impl.juel.ExpressionAccessPolicy;
import org.cibseven.bpm.impl.juel.ExpressionHardeningMode;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.cibseven.commons.testing.ProcessEngineLoggingRule;
import org.cibseven.commons.testing.WatchLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The deny list {@code deniedExpressionAccess} on every expression path of the engine (CIB7-2652).
 */
public class DeniedExpressionAccessTest {

  protected static final String CLASS_ACCESS = "${''.getClass().simpleName}";
  /** Allows CLASS_ACCESS: getClass() and its Class result. */
  protected static final String ALLOW_CLASS_ACCESS = "-getClass,-java.lang.Class";
  /** Removes every default entry: only the protected types stay denied. */
  protected static final String WITHOUT_DEFAULTS =
      "-" + ExpressionAccessPolicy.DEFAULT_DENIED_EXPRESSION_ACCESS.replace(",", ",-");
  protected static final List<String> DEFAULTS =
      Arrays.asList(ExpressionAccessPolicy.DEFAULT_DENIED_EXPRESSION_ACCESS.split(","));

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

  @Test
  public void shouldDenyClassAccessInBpmnExpressionByDefault() {
    buildEngine(configuration -> { });
    deploy(inputMappingProcess(CLASS_ACCESS));

    assertThatThrownBy(this::startProcess)
        .isInstanceOf(ProcessEngineException.class)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("deniedExpressionAccess entry 'getClass'");
  }

  @Test
  public void shouldDenyClassPropertyInBpmnExpressionByDefault() {
    buildEngine(configuration -> { });
    deploy(inputMappingProcess("${execution['class']}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("deniedExpressionAccess entry 'class'");
  }

  @Test
  public void shouldDenyClassAccessInJuelScriptByDefault() {
    buildEngine(configuration -> { });
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("deniedExpressionAccess entry 'getClass'");
  }

  @Test
  public void shouldDenyClassAccessInDmnLiteralExpressionByDefault() {
    buildEngine(configuration -> { });
    engine.getRepositoryService().createDeployment()
        .addString("decision.dmn", literalExpressionDecision(CLASS_ACCESS))
        .deploy();

    assertThatThrownBy(() -> engine.getDecisionService().evaluateDecisionByKey("decision").evaluate())
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldAllowClassAccessWhenDefaultEntriesAreRemoved() {
    buildEngine(configuration -> configuration.setDeniedExpressionAccess(ALLOW_CLASS_ACCESS));
    deploy(inputMappingProcess(CLASS_ACCESS));

    assertThat(startProcessAndGetResult()).isEqualTo("String");
  }

  @Test
  public void shouldAllowClassAccessWhenModeIsOff() {
    buildEngine(configuration -> configuration.setExpressionHardeningMode("off"));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThat(startProcessAndGetResult()).isEqualTo("String");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldLogAndEvaluateInReportMode() {
    buildEngine(configuration -> configuration.setExpressionHardeningMode("report"));
    deploy(inputMappingProcess(CLASS_ACCESS));

    assertThat(startProcessAndGetResult()).isEqualTo("String");
    assertThat(loggingRule.getFilteredLog("deniedExpressionAccess entry 'getClass'")).hasSize(1);
  }

  @Test
  public void shouldDenyConfiguredFunctionInJuelScript() {
    // lang:import is a default entry: removed, and denied again by a configured entry
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("-lang:import,lang:*"));
    deploy(juelScriptProcess("#{lang:import('math', 'java.lang.Math')}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("function 'lang:import'")
        .hasMessageContaining("entry 'lang:*'");
  }

  @Test
  public void shouldKeepDeniedFunctionOutOfScriptContext() {
    // otherwise context.getAttribute('lang:import').invoke(...) would bypass the function check
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("-lang:import,lang:*"));
    deploy(juelScriptProcess("#{context.getAttribute('lang:import') == null}"));

    assertThat(startProcessAndGetResult()).isEqualTo(true);
  }

  @Test
  public void shouldKeepFunctionDeniedByDeclaringTypeOutOfScriptContext() {
    buildEngine(configuration -> configuration.setDeniedExpressionAccess(
        "org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngine"));
    deploy(juelScriptProcess("#{context.getAttribute('lang:import') == null}"));

    assertThat(startProcessAndGetResult()).isEqualTo(true);
  }

  @Test
  public void shouldNotImportDeniedFunctionIntoScriptContext() {
    // otherwise context.getAttribute('m:abs').invoke(...) would bypass the member entry
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("-lang:import,java.lang.Math#abs"));
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    ScriptContext scriptContext = new SimpleScriptContext();

    configuration.getCommandExecutorTxRequired().execute(commandContext -> {
      try {
        return new JuelScriptEngine().eval("${lang:import(context, 'm', 'java.lang.Math')}", scriptContext);
      } catch (ScriptException e) {
        throw new IllegalStateException(e);
      }
    });

    assertThat(scriptContext.getAttribute("m:abs")).isNull();
    assertThat(scriptContext.getAttribute("m:max")).isNotNull();
  }

  @Test
  public void shouldKeepDeniedFunctionInScriptContextInReportMode() {
    buildEngine(configuration -> configuration
        .setDeniedExpressionAccess("-lang:import,lang:*")
        .setExpressionHardeningMode("report"));
    deploy(juelScriptProcess("#{context.getAttribute('lang:import') != null}"));

    assertThat(startProcessAndGetResult()).isEqualTo(true);
  }

  @Test
  public void shouldDenyConfiguredTypeInBpmnExpression() {
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("org.cibseven.bpm.engine.ProcessEngineServices"));
    deploy(inputMappingProcess("${execution.processEngineServices.name}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'org.cibseven.bpm.engine.ProcessEngineServices'");
  }

  @Test
  public void shouldKeepOrdinaryExpressions() {
    buildEngine(configuration -> { });
    deploy(inputMappingProcess("${execution.getVariable('name').toUpperCase()}"));

    ProcessInstance processInstance = engine.getRuntimeService()
        .startProcessInstanceByKey("process", Variables.createVariables().putValue("name", "abc"));

    assertThat(engine.getRuntimeService().getVariable(processInstance.getId(), "result")).isEqualTo("ABC");
  }

  @Test
  public void shouldApplyConfiguredPolicyToExpressionManager() {
    buildEngine(configuration -> configuration
        .setDeniedExpressionAccess("java.lang.System")
        .setExpressionHardeningMode("REPORT"));

    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    JuelExpressionManager expressionManager = (JuelExpressionManager) configuration.getExpressionManager();

    assertThat(expressionManager.getExpressionAccessPolicy()).isSameAs(configuration.getExpressionAccessPolicy());
    assertThat(configuration.getExpressionAccessPolicy().getDeniedEntries())
        .containsAll(DEFAULTS).contains("java.lang.System").hasSize(DEFAULTS.size() + 1);
    assertThat(configuration.getExpressionAccessPolicy().getMode()).isEqualTo(ExpressionHardeningMode.REPORT);
    assertThat(configuration.getDmnEngineConfiguration().getExpressionAccessPolicy())
        .isSameAs(configuration.getExpressionAccessPolicy());
  }

  @Test
  public void shouldRejectInvalidMode() {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneInMemProcessEngineConfiguration();

    assertThatThrownBy(() -> configuration.setExpressionHardeningMode("warn"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("off, report, enforce");
  }

  @Test
  public void shouldDenyAccessToUnrestrictedResolverChain() {
    buildEngine(configuration -> configuration.setDeniedExpressionAccess(WITHOUT_DEFAULTS));
    deploy(inputMappingProcess("${execution.processEngine.processEngineConfiguration.expressionManager.elResolver}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'org.cibseven.bpm.impl.juel'");
  }

  @Test
  public void shouldDenyAccessToElContextInJuelScript() {
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("-class,-getClass"));
    deploy(juelScriptProcess("#{context.getAttribute('elcontext').ELResolver}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'jakarta.el'");
  }

  @Test
  public void shouldDenyLangImportInJuelScriptByDefault() {
    buildEngine(configuration -> { });
    deploy(juelScriptProcess("#{lang:import(context, 'rt', 'java.lang.Runtime')}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'lang:import'");
  }

  @Test
  public void shouldApplyPropertiesSetAfterPolicyWasRead() {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    configuration.setProcessEngineName(getClass().getSimpleName());
    ExpressionAccessPolicy earlier = configuration.getExpressionAccessPolicy();

    configuration.setDeniedExpressionAccess("java.lang.System");
    configuration.setExpressionHardeningMode("report");
    engine = configuration.buildProcessEngine();

    // a setter drops the policy read before
    ExpressionAccessPolicy policy = configuration.getExpressionAccessPolicy();
    assertThat(policy).isNotSameAs(earlier);
    assertThat(policy.getDeniedEntries()).contains("java.lang.System");
    assertThat(policy.getMode()).isEqualTo(ExpressionHardeningMode.REPORT);
    assertThat(((JuelExpressionManager) configuration.getExpressionManager()).getExpressionAccessPolicy()).isSameAs(policy);
    assertThat(configuration.getDmnEngineConfiguration().getExpressionAccessPolicy()).isSameAs(policy);
  }

  @Test
  public void shouldKeepExtensionPointsOfConfigurationPackageReachable() {
    // the default denies the configuration type, not its package with types customers implement
    Map<Object, Object> beans = new HashMap<>();
    beans.put("plugin", new AbstractProcessEnginePlugin() {
      @Override
      public String toString() {
        return "plugin";
      }
    });
    buildEngine(configuration -> configuration.setBeans(beans));
    deploy(inputMappingProcess("${plugin.toString()}"));

    assertThat(startProcessAndGetResult()).isEqualTo("plugin");
  }

  @Test
  public void shouldDenyClassFromHarmlessLookingGetterByDefault() {
    // the route that made java.lang.Class a default: no getClass, a getter that returns a Class
    buildEngine(configuration -> { });
    deploy(inputMappingProcess("${execution.getVariableTyped('name').type.javaType.simpleName}"));

    assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("process",
        Variables.createVariables().putValue("name", "abc")))
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'java.lang.Class'");
  }

  @Test
  public void shouldDenyProcessEngineConfigurationByDefault() {
    buildEngine(configuration -> { });
    deploy(inputMappingProcess("${execution.processEngine.processEngineConfiguration.authorizationEnabled}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl'");
  }

  @Test
  public void shouldNotLetExpressionSwitchOffHardening() {
    // the configuration is denied by default; without that entry, the policy is still frozen
    buildEngine(configuration -> configuration.setDeniedExpressionAccess("-org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl"));
    deploy(inputMappingProcess("${execution.processEngine.processEngineConfiguration.setExpressionHardeningMode('off')}"));

    assertThatThrownBy(this::startProcess)
        .hasMessageContaining("cannot be changed after the process engine build started");

    // still enforced for scripts and expressions
    engine.getRepositoryService().deleteDeployment(
        engine.getRepositoryService().createDeploymentQuery().singleResult().getId(), true);
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));
    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldRejectChangesAfterBuild() {
    buildEngine(configuration -> { });
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

    assertThatThrownBy(() -> configuration.setDeniedExpressionAccess("-getClass"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'deniedExpressionAccess' cannot be changed");
    assertThatThrownBy(() -> configuration.setExpressionHardeningMode("off"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("'expressionHardeningMode' cannot be changed");
  }

  @Test
  public void shouldDenyClassAccessInJuelScriptWithCustomExpressionManager() {
    // a subclass applies its chain to juel scripts (isAppliedToJuelScripts)
    JuelExpressionManager expressionManager = new JuelExpressionManager() { };
    buildEngine(configuration -> configuration.setExpressionManager(expressionManager));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThat(expressionManager.isAppliedToJuelScripts()).isTrue();
    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("entry 'getClass'");
  }

  @Test
  public void shouldKeepPolicyOfCustomExpressionManager() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    ExpressionAccessPolicy customPolicy = ExpressionAccessPolicy.create(ALLOW_CLASS_ACCESS, ExpressionHardeningMode.ENFORCE);
    expressionManager.setExpressionAccessPolicy(customPolicy);
    buildEngine(configuration -> configuration.setExpressionManager(expressionManager));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    ExpressionAccessPolicy effectivePolicy = expressionManager.getExpressionAccessPolicy();
    assertThat(effectivePolicy.getDeniedEntries()).doesNotContain("getClass", "java.lang.Class").contains("class");
    // the custom policy applies on every path and reports through the engine's logger
    assertThat(effectivePolicy.hasDefaultReporter()).isFalse();
    assertThat(configuration.getExpressionAccessPolicy()).isSameAs(effectivePolicy);
    assertThat(configuration.getDmnEngineConfiguration().getExpressionAccessPolicy()).isSameAs(effectivePolicy);
    assertThat(startProcessAndGetResult()).isEqualTo("String");
  }

  @Test
  public void shouldDenyFunctionByDeclaringTypeInJuelScript() {
    // lang:import is declared by the JUEL script engine; its name is a default entry, removed here
    buildEngine(configuration -> configuration.setDeniedExpressionAccess(
        "-lang:import,org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngine"));
    deploy(juelScriptProcess("#{lang:import('math', 'java.lang.Math')}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class)
        .hasMessageContaining("function 'lang:import' declared by");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldLogAndEvaluateJuelScriptInReportMode() {
    buildEngine(configuration -> configuration.setExpressionHardeningMode("report"));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThat(startProcessAndGetResult()).isEqualTo("String");
    assertThat(loggingRule.getFilteredLog("deniedExpressionAccess entry 'getClass'")).hasSize(1);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldLogAndEvaluateDmnLiteralExpressionInReportMode() {
    buildEngine(configuration -> configuration.setExpressionHardeningMode("report"));
    engine.getRepositoryService().createDeployment()
        .addString("decision.dmn", literalExpressionDecision(CLASS_ACCESS))
        .deploy();

    Object result = engine.getDecisionService().evaluateDecisionByKey("decision").evaluate().getSingleEntry();

    assertThat(result).isEqualTo("String");
    assertThat(loggingRule.getFilteredLog("deniedExpressionAccess entry 'getClass'")).hasSize(1);
  }

  @Test
  public void shouldApplyConfiguredPolicyToLegacyFeelEngine() {
    buildEngine(configuration -> configuration
        .setDeniedExpressionAccess("java.lang.System")
        .setDmnFeelEnableLegacyBehavior(true));

    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    FeelEngineFactoryImpl feelEngineFactory = (FeelEngineFactoryImpl) configuration.getDmnEngineConfiguration().getFeelEngineFactory();

    assertThat(feelEngineFactory.getExpressionAccessPolicy()).isSameAs(configuration.getExpressionAccessPolicy());
  }

  @Test
  public void shouldRejectInvalidEntry() {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneInMemProcessEngineConfiguration();

    assertThatThrownBy(() -> configuration.setDeniedExpressionAccess("-execution"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("only default entries can be removed");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldWarnWhenPropertiesAreIgnoredForCustomPolicy() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create("-getClass", ExpressionHardeningMode.ENFORCE));
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setDeniedExpressionAccess("java.lang.System"));

    assertThat(loggingRule.getFilteredLog("ENGINE-25012")).hasSize(1);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnWhenPropertiesDoNotChangeThePolicy() {
    // only separators: the configured policy has the default rules
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create("-getClass", ExpressionHardeningMode.ENFORCE));
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setDeniedExpressionAccess(" , "));

    assertThat(loggingRule.getFilteredLog("ENGINE-25012")).isEmpty();
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnWhenPropertiesMatchThePolicyOfTheCustomExpressionManager() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create("java.lang.System", ExpressionHardeningMode.ENFORCE));
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setDeniedExpressionAccess("java.lang.System"));

    assertThat(loggingRule.getFilteredLog("ENGINE-25012")).isEmpty();
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnWhenBothPoliciesAreOff() {
    // in mode off, the entries do not matter
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF));
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setExpressionHardeningMode("off")
        .setDeniedExpressionAccess("java.lang.System"));

    assertThat(loggingRule.getFilteredLog("ENGINE-25012")).isEmpty();
  }

  @Test
  public void shouldKeepCustomReporterOfCustomExpressionManager() {
    List<String> reports = new ArrayList<>();
    ExpressionAccessPolicy policy = ExpressionAccessPolicy.create(null, ExpressionHardeningMode.REPORT)
        .withReporter(reports::add);
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(policy);
    buildEngine(configuration -> configuration.setExpressionManager(expressionManager));
    deploy(inputMappingProcess(CLASS_ACCESS));

    assertThat(startProcessAndGetResult()).isEqualTo("String");
    assertThat(expressionManager.getExpressionAccessPolicy()).isSameAs(policy);
    assertThat(reports).anyMatch(report -> report.contains("entry 'getClass'"));
  }

  @Test
  public void shouldKeepCustomerRestrictionInJuelScriptInOffMode() {
    // the customer's createElResolver() restricts the chain itself
    JuelExpressionManager expressionManager = new JuelExpressionManager() {
      @Override
      protected jakarta.el.ELResolver createElResolver() {
        return ExpressionAccessPolicy.DEFAULT.restrict(super.createElResolver());
      }
    };
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setExpressionHardeningMode("off"));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyClassAccessInJuelScriptWithNonJuelExpressionManager() {
    buildEngine(configuration -> configuration.setExpressionManager(new DelegatingExpressionManager(new JuelExpressionManager())));
    deploy(juelScriptProcess("#{''.getClass().simpleName}"));

    assertThatThrownBy(this::startProcess)
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  public void shouldDenyClassAccessInJuelScriptWithoutCommandContext() {
    javax.script.ScriptEngine scriptEngine = new org.cibseven.bpm.engine.impl.scripting.engine.JuelScriptEngineFactory().getScriptEngine();

    assertThatThrownBy(() -> scriptEngine.eval("${''.getClass().simpleName}"))
        .hasRootCauseInstanceOf(ExpressionAccessDeniedException.class);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldKeepPolicyOfExpressionManagerUsedBeforeBuild() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    // used before the build
    expressionManager.createValueExpression("${true}");
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setDeniedExpressionAccess("-getClass"));

    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    assertThat(loggingRule.getFilteredLog("ENGINE-25013")).hasSize(1);
    assertThat(expressionManager.getExpressionAccessPolicy()).isSameAs(ExpressionAccessPolicy.DEFAULT);
    assertThat(configuration.getExpressionAccessPolicy()).isSameAs(ExpressionAccessPolicy.DEFAULT);
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnForExpressionManagerUsedBeforeBuildWithPropertiesOfSameContent() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.createValueExpression("${true}");
    // a default entry once more: the same rules as the manager's default policy
    buildEngine(configuration -> configuration
        .setExpressionManager(expressionManager)
        .setDeniedExpressionAccess("class"));

    assertThat(loggingRule.getFilteredLog("ENGINE-25013")).isEmpty();
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnForExpressionManagerUsedBeforeBuildWithoutProperties() {
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.createValueExpression("${true}");
    buildEngine(configuration -> configuration.setExpressionManager(expressionManager));

    assertThat(loggingRule.getFilteredLog("ENGINE-25013")).isEmpty();
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnForExpressionManagerUsedBeforeBuildWithConfiguredPolicy() {
    // the manager already holds the policy of this configuration, e.g. from an earlier build
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    configuration.setProcessEngineName(getClass().getSimpleName());
    configuration.setDeniedExpressionAccess("java.lang.System");
    JuelExpressionManager expressionManager = new JuelExpressionManager();
    expressionManager.setExpressionAccessPolicy(configuration.getExpressionAccessPolicy());
    expressionManager.createValueExpression("${true}");
    configuration.setExpressionManager(expressionManager);

    engine = configuration.buildProcessEngine();

    assertThat(loggingRule.getFilteredLog("ENGINE-25013")).isEmpty();
    assertThat(configuration.getExpressionAccessPolicy()).isSameAs(expressionManager.getExpressionAccessPolicy());
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldNotWarnForExpressionManagerWithoutRestrictionInOffMode() {
    buildEngine(configuration -> configuration
        .setExpressionManager(new DelegatingExpressionManager(new JuelExpressionManager()))
        .setExpressionHardeningMode("off"));

    assertThat(loggingRule.getFilteredLog("ENGINE-25011")).isEmpty();
  }

  @Test
  public void shouldNotChangeExpressionManagerPolicyAfterBuild() {
    buildEngine(configuration -> { });
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    JuelExpressionManager expressionManager = (JuelExpressionManager) configuration.getExpressionManager();
    deploy(inputMappingProcess("${true}"));
    startProcess();

    assertThatThrownBy(() -> expressionManager.setExpressionAccessPolicy(ExpressionAccessPolicy.create(null, ExpressionHardeningMode.OFF)))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("cannot be changed");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldWarnForExpressionManagerWithoutRestriction() {
    buildEngine(configuration -> configuration.setExpressionManager(new DelegatingExpressionManager(new JuelExpressionManager())));

    assertThat(loggingRule.getFilteredLog("ENGINE-25011")).hasSize(1);
  }

  @Test
  public void shouldRejectChangesAfterFailedBuild() {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    configuration.setProcessEngineName(getClass().getSimpleName());
    configuration.getProcessEnginePlugins().add(new AbstractProcessEnginePlugin() {
      @Override
      public void postInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
        throw new IllegalStateException("build failed");
      }
    });
    assertThatThrownBy(configuration::buildProcessEngine).hasMessageContaining("build failed");

    // a change would not reach the expression manager, DMN or FEEL any more
    assertThatThrownBy(() -> configuration.setDeniedExpressionAccess("-getClass"))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("cannot be changed after the process engine build started");
  }

  @Test
  @WatchLogger(loggerNames = {"org.cibseven.bpm.engine.security"}, level = "WARN")
  public void shouldReportEveryExpressionInReportMode() {
    // without java.lang.Class: one report per expression, for getClass()
    buildEngine(configuration -> configuration
        .setDeniedExpressionAccess("-java.lang.Class")
        .setExpressionHardeningMode("report"));
    deploy(inputMappingProcess(CLASS_ACCESS));
    startProcess();
    startProcess();
    engine.getRepositoryService().createDeployment()
        .addModelInstance("other.bpmn", inputMappingProcess("${'abc'.getClass().name}"))
        .deploy();
    startProcess();

    assertThat(loggingRule.getFilteredLog("ENGINE-25010")).hasSize(2);
    assertThat(loggingRule.getFilteredLog("in expression '" + CLASS_ACCESS + "'")).hasSize(1);
  }

  protected void startProcess() {
    engine.getRuntimeService().startProcessInstanceByKey("process");
  }

  protected Object startProcessAndGetResult() {
    ProcessInstance processInstance = engine.getRuntimeService().startProcessInstanceByKey("process");
    return engine.getRuntimeService().getVariable(processInstance.getId(), "result");
  }

  protected BpmnModelInstance inputMappingProcess(String expression) {
    return Bpmn.createExecutableProcess("process")
        .startEvent()
        .serviceTask("task")
          .camundaExpression("${true}")
          .camundaInputParameter("local", expression)
          .camundaOutputParameter("result", "${local}")
        .userTask("wait")
        .endEvent()
        .done();
  }

  protected BpmnModelInstance juelScriptProcess(String script) {
    return Bpmn.createExecutableProcess("process")
        .startEvent()
        .scriptTask("script")
          .scriptFormat("juel")
          .scriptText(script)
          .camundaResultVariable("result")
        .userTask("wait")
        .endEvent()
        .done();
  }

  protected String literalExpressionDecision(String expression) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"definitions\" name=\"definitions\""
        + " namespace=\"http://camunda.org/schema/1.0/dmn\">"
        + "<decision id=\"decision\" name=\"decision\">"
        + "<variable name=\"result\" typeRef=\"string\" />"
        + "<literalExpression expressionLanguage=\"juel\"><text>" + expression + "</text></literalExpression>"
        + "</decision>"
        + "</definitions>";
  }

  protected void deploy(BpmnModelInstance process) {
    engine.getRepositoryService().createDeployment().addModelInstance("process.bpmn", process).deploy();
  }

  /** An expression manager that is not a {@link JuelExpressionManager}. */
  protected static class DelegatingExpressionManager implements ExpressionManager, ElProviderCompatible {

    protected final JuelExpressionManager delegate;

    DelegatingExpressionManager(JuelExpressionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public Expression createExpression(String expression) {
      return delegate.createExpression(expression);
    }

    @Override
    public void addFunction(String name, java.lang.reflect.Method function) {
      delegate.addFunction(name, function);
    }

    @Override
    public ElProvider toElProvider() {
      return delegate.toElProvider();
    }
  }

  protected void buildEngine(Consumer<ProcessEngineConfigurationImpl> configurator) {
    ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createProcessEngineConfigurationFromResource("camunda.cfg.xml");
    configuration.setProcessEngineName(getClass().getSimpleName());
    configurator.accept(configuration);
    engine = configuration.buildProcessEngine();
  }
}
