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
package org.cibseven.bpm.engine.test.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.EntityTypes;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.batch.Batch;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.history.UserOperationLogEntry;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.RequiredHistoryLevel;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.bpm.model.bpmn.BpmnModelInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

// TESTDOC: -------------------------------------------------------------------------------------
// TESTDOC: Engine-Integrationstest (End-to-End) fuer die asynchrone Batch-Loeschung von Deployments
// TESTDOC: (Ticket CIB7-1597). Getestet wird die komplette Kette:
// TESTDOC:   RepositoryService.deleteDeploymentsAsync(...)  -> DeleteDeploymentsBatchCmd
// TESTDOC:     -> BatchBuilder (legt Batch + Seed-Job an)   -> DeleteDeploymentsJobHandler
// TESTDOC:       -> DeleteDeploymentCmd (loescht je Deployment)
// TESTDOC: Anders als der REST-Test (der die Engine mockt) laeuft hier eine echte Engine mit
// TESTDOC: H2-DB; die Batch-Jobs werden real ueber den ManagementService ausgefuehrt.
// TESTDOC: -------------------------------------------------------------------------------------
public class DeleteDeploymentsBatchTest {

  // TESTDOC: Echte (In-Memory-)Prozess-Engine fuer den Test. ProvidedProcessEngineRule stellt die
  // TESTDOC: gemeinsam genutzte Test-Engine bereit; ProcessEngineTestRule liefert Test-Hilfsmethoden.
  protected ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  // TESTDOC: RuleChain stellt die Reihenfolge sicher: erst engineRule (Engine starten), dann testRule.
  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  protected RepositoryService repositoryService;
  protected ManagementService managementService;
  protected RuntimeService runtimeService;
  protected HistoryService historyService;
  protected IdentityService identityService;

  // TESTDOC: Merkt sich die Default-Konfiguration bzw. alle im Test angelegten Deployment-Ids,
  // TESTDOC: damit tearDown() sauber aufraeumen kann.
  protected int defaultInvocationsPerBatchJob;
  protected final List<String> createdDeploymentIds = new ArrayList<>();

  // TESTDOC: setUp(): laeuft vor JEDER Testmethode. Holt die benoetigten Engine-Services und
  // TESTDOC: sichert den Ausgangswert von invocationsPerBatchJob (den ein Test veraendert).
  @Before
  public void setUp() {
    repositoryService = engineRule.getRepositoryService();
    managementService = engineRule.getManagementService();
    runtimeService = engineRule.getRuntimeService();
    historyService = engineRule.getHistoryService();
    identityService = engineRule.getIdentityService();
    defaultInvocationsPerBatchJob = engineRule.getProcessEngineConfiguration().getInvocationsPerBatchJob();
  }

  // TESTDOC: tearDown(): laeuft nach JEDER Testmethode. KEIN Test, sondern reines Aufraeumen, damit
  // TESTDOC: sich Zustand nicht in andere Tests der gemeinsam genutzten Engine "verleckt":
  // TESTDOC:   - evtl. gesetzten angemeldeten Benutzer zuruecksetzen
  // TESTDOC:   - invocationsPerBatchJob auf den Originalwert zuruecksetzen
  // TESTDOC:   - alle (laufenden) Batches + deren HistoricBatch-Eintraege entfernen
  // TESTDOC:   - alle noch existierenden, im Test angelegten Deployments entfernen
  @After
  public void tearDown() {
    identityService.clearAuthentication();
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(defaultInvocationsPerBatchJob);

    for (Batch batch : managementService.createBatchQuery().list()) {
      managementService.deleteBatch(batch.getId(), true);
    }
    for (HistoricBatch historicBatch : historyService.createHistoricBatchQuery().list()) {
      historyService.deleteHistoricBatch(historicBatch.getId());
    }
    for (String deploymentId : createdDeploymentIds) {
      if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0) {
        repositoryService.deleteDeployment(deploymentId, true);
      }
    }
    createdDeploymentIds.clear();
  }

  // ---------------------------------------------------------------- helpers

  // TESTDOC: deploy(): Hilfsmethode (kein Test). Legt ein Deployment mit genau einem ausfuehrbaren
  // TESTDOC: Prozess (Start -> UserTask -> Ende) an und merkt sich dessen Id fuer die Aufraeum-Phase.
  // TESTDOC: Der UserTask sorgt dafuer, dass gestartete Instanzen "haengen bleiben" (fuer den Cascade-Test).
  // TESTDOC: 'source' erlaubt es, Deployments spaeter per Query gezielt zu selektieren.
  protected String deploy(String processKey, String source) {
    BpmnModelInstance model = Bpmn.createExecutableProcess(processKey)
        .startEvent()
        .userTask("task")
        .endEvent()
        .done();

    Deployment deployment = repositoryService.createDeployment()
        .name(processKey)
        .source(source)
        .addModelInstance(processKey + ".bpmn", model)
        .deploy();

    createdDeploymentIds.add(deployment.getId());
    return deployment.getId();
  }

  // TESTDOC: deploymentExists(): prueft, ob ein Deployment mit der gegebenen Id noch in der DB liegt.
  protected boolean deploymentExists(String deploymentId) {
    return repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0;
  }

  // TESTDOC: executeBatch(): fuehrt einen Batch vollstaendig aus (simuliert den Job-Executor):
  // TESTDOC:   1) alle Seed-Jobs ausfuehren -> diese erzeugen die eigentlichen Ausfuehrungs-Jobs
  // TESTDOC:   2) Ausfuehrungs-Jobs (batchJobDefinitionId) ausfuehren, dabei die Liste nach jeder Runde
  // TESTDOC:      neu abfragen und wiederholen, bis keine mehr uebrig sind. Das ist robust, falls ein
  // TESTDOC:      Job weitere Jobs erzeugen/veraendern wuerde, verkraftet mehrere Jobs pro Runde (bei
  // TESTDOC:      invocationsPerBatchJob=1 entsteht ein Job je Deployment) und entspricht dem Vorgehen
  // TESTDOC:      des engine-eigenen BatchHelper.completeExecutionJobs(). Ein einmaliges list() waere
  // TESTDOC:      hier fragil; ein singleResult() wuerde bei >1 Job sogar eine Exception werfen.
  protected void executeBatch(Batch batch) {
    // run all seed jobs -> creates the execution jobs
    Job seedJob = getSeedJob(batch);
    while (seedJob != null) {
      managementService.executeJob(seedJob.getId());
      seedJob = getSeedJob(batch);
    }
    // run execution jobs, re-querying until none remain -> perform the actual deletions
    List<Job> executionJobs;
    while (!(executionJobs = managementService.createJobQuery()
        .jobDefinitionId(batch.getBatchJobDefinitionId()).list()).isEmpty()) {
      for (Job job : executionJobs) {
        managementService.executeJob(job.getId());
      }
    }
  }

  // TESTDOC: getSeedJob(): liefert den (aktuell offenen) Seed-Job des Batches oder null.
  protected Job getSeedJob(Batch batch) {
    return managementService.createJobQuery().jobDefinitionId(batch.getSeedJobDefinitionId()).singleResult();
  }

  // ---------------------------------------------------------------- creation

  // TESTDOC: TEST: Batch-Erstellung setzt den korrekten Batch-Typ und die richtige Anzahl Jobs.
  // TESTDOC: Erwartung: Typ == "deployment-deletion"; bei 2 Deployments und Default
  // TESTDOC: invocationsPerBatchJob (=1) entstehen genau 2 Jobs. Es wird NICHT ausgefuehrt.
  @Test
  public void shouldCreateBatchWithCorrectType() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);

    assertThat(batch).isNotNull();
    assertThat(batch.getType()).isEqualTo(Batch.TYPE_DEPLOYMENT_DELETION);
    assertThat(batch.getTotalJobs()).isEqualTo(2);
  }

  // TESTDOC: TEST: Zu einem erstellten Batch existiert ein passender HistoricBatch-Eintrag
  // TESTDOC: (Grundlage fuer das Monitoring der Batch-Operation im Cockpit).
  @Test
  public void shouldCreateHistoricBatch() {
    String d1 = deploy("process1", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Collections.singletonList(d1), null, false, false, false);

    HistoricBatch historicBatch = historyService.createHistoricBatchQuery().batchId(batch.getId()).singleResult();
    assertThat(historicBatch).isNotNull();
    assertThat(historicBatch.getType()).isEqualTo(Batch.TYPE_DEPLOYMENT_DELETION);
  }

  // TESTDOC: TEST: Die Konfiguration invocationsPerBatchJob steuert die Job-Aufteilung.
  // TESTDOC: Mit invocationsPerBatchJob=2 und 3 Deployments erwarten wir ceil(3/2)=2 Ausfuehrungs-Jobs.
  @Test
  public void shouldRespectInvocationsPerBatchJob() {
    engineRule.getProcessEngineConfiguration().setInvocationsPerBatchJob(2);
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");
    String d3 = deploy("process3", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2, d3), null, false, false, false);

    // ceil(3 / 2) = 2 execution jobs
    assertThat(batch.getTotalJobs()).isEqualTo(2);
  }

  // ---------------------------------------------------------------- deletion

  // TESTDOC: TEST (Happy Path per Ids): explizit uebergebene Deployment-Ids werden nach vollstaendiger
  // TESTDOC: Batch-Ausfuehrung tatsaechlich aus der DB geloescht.
  @Test
  public void shouldDeleteDeploymentsByIds() {
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  // TESTDOC: TEST (Happy Path per Query): Statt Ids wird eine DeploymentQuery uebergeben (hier: nach
  // TESTDOC: 'source'). Alle von der Query getroffenen Deployments werden geloescht.
  @Test
  public void shouldDeleteDeploymentsByQuery() {
    String source = "query-only-source";
    String d1 = deploy("process1", source);
    String d2 = deploy("process2", source);

    Batch batch = repositoryService.deleteDeploymentsAsync(
        null, repositoryService.createDeploymentQuery().deploymentSource(source), false, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  // TESTDOC: TEST (Ids + Query kombiniert, mit Dedup): d1 wird SOWOHL explizit als Id ALS AUCH ueber die
  // TESTDOC: Query geliefert. Erwartung: d1 wird nur EINMAL verarbeitet -> totalJobs == 2 (nicht 3),
  // TESTDOC: und am Ende sind beide Deployments geloescht.
  @Test
  public void shouldMergeIdsAndQueryAndDeduplicate() {
    String source = "merge-source";
    String d1 = deploy("process1", source);
    String d2 = deploy("process2", source);

    // d1 provided both explicitly and via the query -> must be deduplicated
    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1),
        repositoryService.createDeploymentQuery().deploymentSource(source),
        false, false, false);

    assertThat(batch.getTotalJobs()).isEqualTo(2);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
    assertThat(deploymentExists(d2)).isFalse();
  }

  // TESTDOC: TEST (Dedup innerhalb der Id-Liste): dieselbe Id doppelt uebergeben -> nur 1 Job.
  @Test
  public void shouldDeduplicateDuplicateExplicitIds() {
    String d1 = deploy("process1", "src");

    Batch batch = repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d1), null, false, false, false);

    assertThat(batch.getTotalJobs()).isEqualTo(1);

    executeBatch(batch);
    assertThat(deploymentExists(d1)).isFalse();
  }

  // TESTDOC: TEST (cascade=true): Ein Deployment mit einer laufenden Prozessinstanz wird geloescht.
  // TESTDOC: Ohne cascade wuerde die Loeschung an der laufenden Instanz scheitern; mit cascade=true
  // TESTDOC: werden Instanz UND Deployment entfernt. Erwartung: das Deployment ist weg und es gibt
  // TESTDOC: keine laufende Instanz MEHR ZU DIESEM Prozess (per processDefinitionKey eingeschraenkt,
  // TESTDOC: damit wirklich unsere Instanz geprueft wird und nicht nur "irgendeine" - robust gegen
  // TESTDOC: evtl. Instanzen anderer Tests auf der gemeinsam genutzten Engine).
  @Test
  public void shouldCascadeDeleteRunningInstances() {
    String d1 = deploy("process1", "src");
    runtimeService.startProcessInstanceByKey("process1");
    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("process1").count()).isEqualTo(1);

    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1), null, true, false, false);
    executeBatch(batch);

    assertThat(deploymentExists(d1)).isFalse();
    assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey("process1").count()).isZero();
  }

  // TESTDOC: TEST (cascade=true, HISTORY-Semantik): Ergaenzt den vorigen Test um die Pruefung der
  // TESTDOC: HISTORISCHEN Daten. Wichtige Camunda-Frage: Runtime geloescht - History behalten oder
  // TESTDOC: auch loeschen? Am Code bestaetigt (DeploymentManager-Kommentar: "cascade true deletes the
  // TESTDOC: history and process instances"; DeleteProcessDefinitionsByIdsCmd mit cascadeToHistory=true):
  // TESTDOC: bei cascade=true wird die History MIT geloescht, also NICHT behalten.
  // TESTDOC: Vorher: HistoricProcessInstance (==1) und HistoricActivityInstance (>0) sind vorhanden.
  // TESTDOC: Nachher: beide sind 0. HistoricActivityInstanceQuery kennt kein processDefinitionKey,
  // TESTDOC: daher wird vor dem Loeschen die processDefinitionId abgegriffen. Braucht History-Level FULL.
  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void shouldCascadeAlsoDeleteHistory() {
    String d1 = deploy("process1", "src");
    String processDefinitionId = repositoryService.createProcessDefinitionQuery()
        .processDefinitionKey("process1").singleResult().getId();
    runtimeService.startProcessInstanceByKey("process1");

    // history has been recorded for the running instance
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processDefinitionKey("process1").count()).isEqualTo(1);
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processDefinitionId(processDefinitionId).count()).isPositive();

    Batch batch = repositoryService.deleteDeploymentsAsync(
        Collections.singletonList(d1), null, true, false, false);
    executeBatch(batch);

    // cascade=true also removes the history (it is not kept)
    assertThat(historyService.createHistoricProcessInstanceQuery()
        .processDefinitionKey("process1").count()).isZero();
    assertThat(historyService.createHistoricActivityInstanceQuery()
        .processDefinitionId(processDefinitionId).count()).isZero();
  }

  // ---------------------------------------------------------------- edge cases

  // TESTDOC: TEST (Edge Case): weder Ids noch Query angegeben -> BadUserRequestException (kein leerer Batch).
  @Test
  public void shouldThrowWhenNeitherIdsNorQueryProvided() {
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(null, null, false, false, false))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("deploymentIds");
  }

  // TESTDOC: TEST (Edge Case): leere Id-Liste und keine Query -> BadUserRequestException.
  @Test
  public void shouldThrowWhenEmptyIdListProvided() {
    assertThatThrownBy(
        () -> repositoryService.deleteDeploymentsAsync(Collections.emptyList(), null, false, false, false))
        .isInstanceOf(BadUserRequestException.class);
  }

  // TESTDOC: TEST (Edge Case): Query liefert keine Treffer -> BadUserRequestException
  // TESTDOC: (es gibt nichts zu loeschen, also wird kein Batch erzeugt).
  @Test
  public void shouldThrowWhenQueryMatchesNothing() {
    assertThatThrownBy(() -> repositoryService.deleteDeploymentsAsync(
        null, repositoryService.createDeploymentQuery().deploymentSource("does-not-exist"), false, false, false))
        .isInstanceOf(BadUserRequestException.class);
  }

  // ---------------------------------------------------------------- operation log

  // TESTDOC: TEST (Audit-Log): Die Batch-Erstellung schreibt einen User-Operation-Log-Eintrag der
  // TESTDOC: Kategorie DEPLOYMENT (nicht Prozessinstanz!) mit Operation "Delete".
  // TESTDOC: Geprueft: Property "nrOfDeployments" == Anzahl der Deployments (hier "2"),
  // TESTDOC: Kategorie == Operator, und Property "async" == "true".
  // TESTDOC: Voraussetzung: History-Level FULL (@RequiredHistoryLevel) + angemeldeter Benutzer,
  // TESTDOC: sonst wird kein Operation-Log geschrieben.
  @Test
  @RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
  public void shouldWriteDeploymentOperationLog() {
    identityService.setAuthenticatedUserId("userId");
    String d1 = deploy("process1", "src");
    String d2 = deploy("process2", "src");

    repositoryService.deleteDeploymentsAsync(Arrays.asList(d1, d2), null, false, false, false);

    UserOperationLogEntry countEntry = historyService.createUserOperationLogQuery()
        .entityType(EntityTypes.DEPLOYMENT)
        .operationType(UserOperationLogEntry.OPERATION_TYPE_DELETE)
        .property("nrOfDeployments")
        .singleResult();
    assertThat(countEntry).isNotNull();
    assertThat(countEntry.getNewValue()).isEqualTo("2");
    assertThat(countEntry.getCategory()).isEqualTo(UserOperationLogEntry.CATEGORY_OPERATOR);

    UserOperationLogEntry asyncEntry = historyService.createUserOperationLogQuery()
        .entityType(EntityTypes.DEPLOYMENT)
        .operationType(UserOperationLogEntry.OPERATION_TYPE_DELETE)
        .property("async")
        .singleResult();
    assertThat(asyncEntry).isNotNull();
    assertThat(asyncEntry.getNewValue()).isEqualTo("true");
  }
}
