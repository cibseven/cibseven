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
package org.cibseven.bpm.quarkus.engine.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.runtime.Incident;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.cibseven.bpm.quarkus.engine.test.helper.ProcessEngineAwareExtension;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * CIB7-1959 Quarkus job executor creates threads without the Quarkus TCCL (SRCFG00015)
 *
 * <p>Verifies that a CDI bean carrying {@code @ConfigProperty} can be created lazily from a
 * job executor thread. The engine switches the thread context class loader to the process
 * engine's class loader while a job runs; SmallRye Config matches the TCCL by exact identity,
 * so unless the Quarkus class loader is kept in place, resolving the injection point fails
 * with {@code SRCFG00015: No configuration is available for this class loader} and the engine
 * turns that into a failed job incident.
 */
public class JobExecutorConfigPropertyTest {

  protected static final long TIMEOUT_MS = 10_000L;
  protected static final long POLL_INTERVAL_MS = 50L;

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new ProcessEngineAwareExtension()
      .withConfigurationResource("org/cibseven/bpm/quarkus/engine/test/config/" +
                                     "config-property-job-application.properties")
      .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
          .addClass(ConfigPropertyDelegate.class)
          .addClass(LazyConfigPropertyBean.class));

  @Inject
  protected RuntimeService runtimeService;

  @Inject
  protected HistoryService historyService;

  @ApplicationScoped
  static class EngineConfigurer {

    @Produces
    public QuarkusProcessEngineConfiguration engineConfiguration() {
      return new QuarkusProcessEngineConfiguration();
    }

  }

  /**
   * Only referenced through the BPMN delegate expression {@code ${configPropertyDelegate}}.
   */
  @Named("configPropertyDelegate")
  @ApplicationScoped
  public static class ConfigPropertyDelegate implements JavaDelegate {

    @Inject
    LazyConfigPropertyBean lazyConfigPropertyBean;

    @Override
    public void execute(DelegateExecution execution) {
      // triggers the first, lazy creation of LazyConfigPropertyBean, on a job executor thread
      execution.setVariable("configValue", lazyConfigPropertyBean.getValue());
    }

  }

  /**
   * Injected only by {@link ConfigPropertyDelegate}, so that its first creation - and with it
   * the resolution of its {@code @ConfigProperty} injection point - happens on a job executor
   * thread rather than during application startup.
   */
  @ApplicationScoped
  public static class LazyConfigPropertyBean {

    protected final String value;

    public LazyConfigPropertyBean(
        @ConfigProperty(name = "cibseven.test.config-property-job.value") String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

  }

  @Test
  @Deployment
  public void shouldExecuteJobUsingConfigPropertyBean() throws InterruptedException {
    // given
    ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("configPropertyJobProcess");
    String processInstanceId = processInstance.getId();

    // when the job executor picks up the asyncBefore job
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {

      List<Incident> incidents = runtimeService.createIncidentQuery()
          .processInstanceId(processInstanceId)
          .list();
      if (!incidents.isEmpty()) {
        Incident incident = incidents.get(0);
        // then no incident is expected; report the cause to make failures diagnosable
        assertThat(incident.getIncidentMessage())
            .withFailMessage("expected no incident, but job execution failed with [%s] %s",
                incident.getIncidentType(), incident.getIncidentMessage())
            .isNull();
      }

      if (runtimeService.createProcessInstanceQuery()
          .processInstanceId(processInstanceId)
          .singleResult() == null) {

        // then the process completed and the config property was resolved on the job thread
        assertThat(historyService.createHistoricVariableInstanceQuery()
            .processInstanceId(processInstanceId)
            .variableName("configValue")
            .singleResult()
            .getValue())
            .isEqualTo("configured-value");
        return;
      }

      Thread.sleep(POLL_INTERVAL_MS);
    }

    assertThat(false)
        .withFailMessage("process instance %s did not complete within %d ms", processInstanceId, TIMEOUT_MS)
        .isTrue();
  }

}
