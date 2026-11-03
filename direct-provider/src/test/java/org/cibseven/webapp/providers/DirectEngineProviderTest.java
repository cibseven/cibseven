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
package org.cibseven.webapp.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.webapp.rest.model.EngineConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

public class DirectEngineProviderTest {

	private DirectProviderUtil directProviderUtil;
	private ProcessEngine processEngine;
	private DirectEngineProvider provider;

	@BeforeEach
	void setUp() {
		processEngine = mock(ProcessEngine.class);
		when(processEngine.getName()).thenReturn("default");

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(String.class));

		provider = new DirectEngineProvider(directProviderUtil);
	}

	@Test
	void getEngineConfiguration_populatesHistoryTimeToLiveAndEnforceFlag() {
		ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
		when(config.getHistory()).thenReturn("full");
		when(config.isAuthorizationEnabled()).thenReturn(true);
		when(config.isEnablePasswordPolicy()).thenReturn(false);
		when(config.getHistoryTimeToLive()).thenReturn("30");
		when(config.isEnforceHistoryTimeToLive()).thenReturn(true);
		when(processEngine.getProcessEngineConfiguration()).thenReturn(config);

		EngineConfiguration result = provider.getEngineConfiguration("default");

		assertThat(result.getHistoryTimeToLive()).isEqualTo(30);
		assertThat(result.getEnforceHistoryTimeToLive()).isTrue();
	}

	@ParameterizedTest
	@CsvSource(value = {"30,30", "P30D,30", "0,0", "P0D,0", "null,-1"}, nullValues = "null")
	void getEngineConfiguration_historyTimeToLive_isReportedInDaysWithMinusOneForUnlimited(String configured, int expected) {
		ProcessEngineConfigurationImpl config = mock(ProcessEngineConfigurationImpl.class);
		when(config.getHistoryTimeToLive()).thenReturn(configured);
		when(config.isEnforceHistoryTimeToLive()).thenReturn(false);
		when(processEngine.getProcessEngineConfiguration()).thenReturn(config);

		EngineConfiguration result = provider.getEngineConfiguration("default");

		assertThat(result.getHistoryTimeToLive()).isEqualTo(expected);
	}

	@Test
	void getEngineConfiguration_engineNotFound_returnsNull() {
		doReturn(null).when(directProviderUtil).getProcessEngine(any(String.class));

		assertThat(provider.getEngineConfiguration("missing")).isNull();
	}

	@Test
	void getEngineConfiguration_configNotAnImpl_reportsTtlFieldsAsUnknown() {
		// getHistoryTimeToLive()/isEnforceHistoryTimeToLive() only exist on the impl class -
		// an engine reporting a bare ProcessEngineConfiguration must not fail. Its real values
		// cannot be read, so both fields are reported as unknown (null), the same as an
		// engine-rest before 3.0.0 that does not report them, rather than as guessed defaults.
		ProcessEngineConfiguration config = mock(ProcessEngineConfiguration.class);
		when(config.getHistory()).thenReturn("full");
		when(processEngine.getProcessEngineConfiguration()).thenReturn(config);

		EngineConfiguration result = provider.getEngineConfiguration("default");

		assertThat(result.getHistoryTimeToLive()).isNull();
		assertThat(result.getEnforceHistoryTimeToLive()).isNull();
	}
}
