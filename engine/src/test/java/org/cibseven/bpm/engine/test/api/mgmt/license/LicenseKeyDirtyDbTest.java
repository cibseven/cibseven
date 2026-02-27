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
package org.cibseven.bpm.engine.test.api.mgmt.license;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.test.util.ProcessEngineTestRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.AfterClass;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Order;


public class LicenseKeyDirtyDbTest {

  @RegisterExtension
  @Order(5) public ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  @RegisterExtension
  @Order(9) public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  ProcessEngine processEngine;
  ProcessEngineConfigurationImpl processEngineConfiguration;
  ManagementService managementService;

  @BeforeEach
  public void init() {
    processEngine = engineRule.getProcessEngine();
    processEngineConfiguration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    managementService = processEngine.getManagementService();
    managementService.setLicenseKey("{\"customer\":\"testCompany\"}");
  }

  @AfterClass
  public static void cleanup() {
    new ProvidedProcessEngineRule().getProcessEngine().getManagementService().deleteLicenseKey();
  }

  @Test
  public void testDirtyDatabaseOnlyLicenseKey() {
    // given
    // license key in ACT_GE_BYTEARRAY

    // when
    String licenseKey = managementService.getLicenseKey();
    Long byteArrayCount = queryByteArrayTableSize();

    // then
    assertThat(licenseKey).isNotEmpty();
    assertThat(byteArrayCount).isEqualTo(1L);
    // the after-test database check should succeed with the license key present
    // in the byte array table
  }

  private Long queryByteArrayTableSize() {
    return processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Long>() {
      @Override
      public Long execute(CommandContext commandContext) {
        String tablePrefix = processEngineConfiguration.getDatabaseTablePrefix();
        return commandContext.getProcessEngineConfiguration().getManagementService().getTableCount().get(tablePrefix + "ACT_GE_BYTEARRAY");
      }
    });
  }
}
