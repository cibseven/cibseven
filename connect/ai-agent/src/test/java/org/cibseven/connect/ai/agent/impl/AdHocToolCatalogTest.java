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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * {@link AdHocToolCatalog} -- what the model tells an agent about the activities it may start.
 *
 * <p>Two things are derived rather than declared, and both are the point of this class. The
 * startable set, because the parser computes it and exposes it nowhere. And the variables an
 * activity is expected to write, because asking the modeller to list them duplicates what the model
 * usually already says: an output mapping, a result variable, or a user task's form fields.
 *
 * <p>The tests deploy real models against a real engine, because the derivation reads the deployed
 * model out of the deployment cache and a hand-built model instance would skip both the deployment
 * and the cache.
 */
public class AdHocToolCatalogTest {

  private static final ProcessEngine ENGINE = buildInMemoryEngine();

  @Rule
  public ProcessEngineRule engineRule = new ProcessEngineRule(ENGINE);

  private static ProcessEngine buildInMemoryEngine() {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:adhoc-tool-catalog-test;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    // Deployment is refused without a history time to live, and these models carry none because
    // the catalogue has nothing to do with history cleanup. Set the global default rather than
    // switching enforceHistoryTimeToLive off, which the engine itself advises against, or
    // repeating the attribute in every model below.
    configuration.setHistoryTimeToLive("P30D");
    return configuration.buildProcessEngine();
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * deployWithResult() rather than deploy(): the latter returns a plain Deployment, which does not
   * carry the definitions it created. Only DeploymentWithDefinitions does, and that is what the
   * test needs to get at the definition id the catalogue is read for.
   */
  private String deploy(String bpmn) {
    return ENGINE.getRepositoryService().createDeployment()
        .addString("catalog.bpmn20.xml", bpmn)
        .deployWithResult()
        .getDeployedProcessDefinitions().get(0)
        .getId();
  }

  private List<AdHocToolCatalog.Entry> read(String bpmn) {
    return AdHocToolCatalog.read(ENGINE.getRepositoryService(), deploy(bpmn), "adHoc");
  }

  private AdHocToolCatalog.Entry entry(List<AdHocToolCatalog.Entry> entries, String id) {
    for (AdHocToolCatalog.Entry candidate : entries) {
      if (id.equals(candidate.getId())) {
        return candidate;
      }
    }
    throw new AssertionError("no catalogue entry for '" + id + "' among " + entries.size());
  }

  /** Wraps the children in a parked ad hoc scope, which is what an agentic model looks like. */
  private static String process(String children) {
    return "<?xml version='1.0' encoding='UTF-8'?>"
        + "<definitions xmlns='http://www.omg.org/spec/BPMN/20100524/MODEL'"
        + " xmlns:camunda='http://camunda.org/schema/1.0/bpmn'"
        + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
        + " targetNamespace='http://cibseven.org/catalog'>"
        + "<process id='catalog' isExecutable='true'>"
        + "<startEvent id='start' />"
        + "<sequenceFlow id='f1' sourceRef='start' targetRef='adHoc' />"
        + "<adHocSubProcess id='adHoc'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='explicitCompletionOnly' value='true' />"
        + "</camunda:properties></extensionElements>"
        + children
        + "</adHocSubProcess>"
        + "<sequenceFlow id='f2' sourceRef='adHoc' targetRef='end' />"
        + "<endEvent id='end' />"
        + "</process></definitions>";
  }

  // --- the startable set -----------------------------------------------------

  @Test
  public void theCatalogueListsTheChildActivities() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='taskA' name='Task A' />"
        + "<userTask id='taskB' name='Task B' />"));

    assertThat(entries).extracting("id").containsExactly("taskA", "taskB");
    assertThat(entry(entries, "taskA").getName()).isEqualTo("Task A");
  }

  /**
   * An intermediate catch event is reachable by sequence flow and never by direct activation, so
   * offering it would produce a request the engine refuses.
   */
  @Test
  public void anElementThatIsNotAnActivityIsNotOffered() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='taskA' />"
        + "<intermediateCatchEvent id='wait'>"
        + "<timerEventDefinition><timeDuration>PT1M</timeDuration></timerEventDefinition>"
        + "</intermediateCatchEvent>"));

    assertThat(entries).extracting("id").containsExactly("taskA");
  }

  /** The documentation is what lets a model choose; only the first entry is used. */
  @Test
  public void theFirstDocumentationEntryIsCarried() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='taskA'>"
        + "<documentation>Decides the invoice.</documentation>"
        + "<documentation>Second one, ignored.</documentation>"
        + "</userTask>"));

    assertThat(entry(entries, "taskA").getDocumentation()).isEqualTo("Decides the invoice.");
  }

  @Test
  public void anActivityWithoutDocumentationCarriesNull() {
    assertThat(entry(read(process("<userTask id='taskA' />")), "taskA").getDocumentation()).isNull();
  }

  // --- result variables, derived ---------------------------------------------

  @Test
  public void outputParameterNamesAreTheResultVariables() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='ignored'>${1}</camunda:inputParameter>"
        + "<camunda:outputParameter name='betrag'>${1200}</camunda:outputParameter>"
        + "<camunda:outputParameter name='geprueft'>${true}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables())
        .as("output parameters only, in document order")
        .containsExactly("betrag", "geprueft");
  }

  @Test
  public void theResultVariableOfAServiceTaskIsAResultVariable() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${1200}' camunda:resultVariable='betrag' />"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag");
  }

  @Test
  public void theResultVariableOfAScriptTaskIsAResultVariable() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<scriptTask id='calc' scriptFormat='juel' camunda:resultVariable='summe'>"
        + "<script>${1 + 1}</script>"
        + "</scriptTask>"));

    assertThat(entry(entries, "calc").getResultVariables()).containsExactly("summe");
  }

  /** A user task's form fields become process variables when the task is completed. */
  @Test
  public void formFieldIdsAreTheResultVariablesOfAUserTask() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='decide'>"
        + "<extensionElements><camunda:formData>"
        + "<camunda:formField id='freigabe' type='boolean' />"
        + "<camunda:formField id='kommentar' type='string' />"
        + "</camunda:formData></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "decide").getResultVariables())
        .containsExactly("freigabe", "kommentar");
  }

  /**
   * An output mapping and a result variable can name the same variable. Reporting it twice would be
   * noise in the prompt, so the derivation deduplicates while keeping document order.
   */
  @Test
  public void aVariableNamedTwiceIsReportedOnce() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${1200}' camunda:resultVariable='betrag'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:outputParameter name='betrag'>${1200}</camunda:outputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag");
  }

  /**
   * Nothing declared is reported as nothing, not guessed. The tool says so explicitly, so a model
   * does not read an empty result as "the activity produced nothing".
   */
  @Test
  public void anActivityThatDeclaresNothingHasNoResultVariables() {
    assertThat(entry(read(process("<userTask id='taskA' />")), "taskA").getResultVariables())
        .isEmpty();
  }

  /** Input parameters are not results and must not leak into the list. */
  @Test
  public void inputParametersAreNotResultVariables() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}'>"
        + "<extensionElements><camunda:inputOutput>"
        + "<camunda:inputParameter name='vorgabe'>${1}</camunda:inputParameter>"
        + "</camunda:inputOutput></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).isEmpty();
  }

  // --- result variables, overridden ------------------------------------------

  /** The property wins, so a modeller can narrow a derivation that offers too much. */
  @Test
  public void theExplicitPropertyOverridesTheDerivation() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}'>"
        + "<extensionElements>"
        + "<camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='betrag' />"
        + "</camunda:properties>"
        + "<camunda:inputOutput>"
        + "<camunda:outputParameter name='betrag'>${1200}</camunda:outputParameter>"
        + "<camunda:outputParameter name='intern'>${false}</camunda:outputParameter>"
        + "</camunda:inputOutput>"
        + "</extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables())
        .as("the derivation offered two, the property narrowed it to one")
        .containsExactly("betrag");
  }

  /** It also extends: a delegate that writes with no mapping anywhere can still be described. */
  @Test
  public void theExplicitPropertyDescribesAnActivityThatDeclaresNothingElse() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='betrag,geprueft' />"
        + "</camunda:properties></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag", "geprueft");
  }

  @Test
  public void thePropertyIsTrimmedAndDeduplicated() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value=' betrag , geprueft ,betrag, ' />"
        + "</camunda:properties></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag", "geprueft");
  }

  /**
   * An empty value falls back to the derivation rather than meaning "show nothing". Pinned because
   * the other reading is defensible -- a modeller might set it empty to hide everything -- so if
   * that turns out to be wanted, this is the test that has to change, deliberately.
   */
  @Test
  public void anEmptyPropertyFallsBackToTheDerivation() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}' camunda:resultVariable='betrag'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocResultVariables' value='' />"
        + "</camunda:properties></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag");
  }

  /** An unrelated property must not be mistaken for the result declaration. */
  @Test
  public void anUnrelatedPropertyIsIgnored() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<serviceTask id='check' camunda:expression='${true}' camunda:resultVariable='betrag'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='somethingElse' value='geprueft' />"
        + "</camunda:properties></extensionElements>"
        + "</serviceTask>"));

    assertThat(entry(entries, "check").getResultVariables()).containsExactly("betrag");
  }

  // --- configuration errors --------------------------------------------------

  /**
   * Both of these throw rather than returning an empty catalogue. An empty catalogue tells the
   * caller "this scope has nothing to start", which would be a false statement about a
   * misconfiguration.
   */
  @Test
  public void anUnknownIdIsRejected() {
    final String definitionId = deploy(process("<userTask id='taskA' />"));

    assertThatThrownBy(new ThrowingCallable() {
      @Override
      public void call() {
        AdHocToolCatalog.read(ENGINE.getRepositoryService(), definitionId, "nosuch");
      }
    })
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nosuch")
        .hasMessageContaining("no element with id");
  }

  @Test
  public void anIdThatIsNotAnAdHocSubProcessIsRejected() {
    final String definitionId = deploy(process("<userTask id='taskA' />"));

    assertThatThrownBy(new ThrowingCallable() {
      @Override
      public void call() {
        AdHocToolCatalog.read(ENGINE.getRepositoryService(), definitionId, "taskA");
      }
    })
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("taskA")
        .hasMessageContaining("not an ad hoc sub process");
  }

  // --- the returned lists ----------------------------------------------------

  @Test
  public void theCatalogueIsUnmodifiable() {
    final List<AdHocToolCatalog.Entry> entries = read(process("<userTask id='taskA' />"));

    assertThatThrownBy(new ThrowingCallable() {
      @Override
      public void call() {
        entries.add(null);
      }
    })
        .as("a caller must not be able to change what the model says")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void theResultVariableListIsUnmodifiable() {
    final List<String> names = entry(read(process(
        "<serviceTask id='check' camunda:expression='${1}' camunda:resultVariable='betrag' />")),
        "check").getResultVariables();

    assertThatThrownBy(new ThrowingCallable() {
      @Override
      public void call() {
        names.add("smuggled");
      }
    })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // --- the blocking marking -------------------------------------------------

  @Test
  public void anActivityIsNotBlockingByDefault() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='plain' />"));

    assertThat(entry(entries, "plain").isBlockedWhileOthersRun()).isFalse();
  }

  @Test
  public void theMarkingIsRead() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='true' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "gated").isBlockedWhileOthersRun()).isTrue();
  }

  @Test
  public void theMarkingIsCaseInsensitiveAndTrimmed() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='  TRUE  ' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "gated").isBlockedWhileOthersRun()).isTrue();
  }

  /** Explicit false means the same as absent, so it can stay in the model. */
  @Test
  public void anExplicitFalseIsNotBlocking() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='false' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "gated").isBlockedWhileOthersRun()).isFalse();
  }

  /**
   * A value that is neither is treated as not blocking and warned about. Recorded
   * deliberately: it means a typo in the value leaves the activity unguarded, and
   * it cannot be refused at deployment because the property sits on a child and
   * is read by the connector, not by the parser.
   */
  @Test
  public void anUnparseableMarkingIsNotBlocking() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='yes' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "gated").isBlockedWhileOthersRun()).isFalse();
  }

  /** An empty value falls back to not blocking, like an absent property. */
  @Test
  public void anEmptyMarkingIsNotBlocking() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    assertThat(entry(entries, "gated").isBlockedWhileOthersRun()).isFalse();
  }

  /** The marking and the result-variable override are independent. */
  @Test
  public void theMarkingDoesNotDisturbTheResultVariables() {
    List<AdHocToolCatalog.Entry> entries = read(process(
        "<userTask id='gated'>"
        + "<extensionElements><camunda:properties>"
        + "<camunda:property name='adHocBlockedWhileOthersRun' value='true' />"
        + "<camunda:property name='adHocResultVariables' value='betrag' />"
        + "</camunda:properties></extensionElements>"
        + "</userTask>"));

    AdHocToolCatalog.Entry gated = entry(entries, "gated");
    assertThat(gated.isBlockedWhileOthersRun()).isTrue();
    assertThat(gated.getResultVariables()).containsExactly("betrag");
  }
}
