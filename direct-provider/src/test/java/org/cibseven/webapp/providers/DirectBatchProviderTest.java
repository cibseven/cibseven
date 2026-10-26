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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.batch.history.HistoricBatch;
import org.cibseven.bpm.engine.batch.history.HistoricBatchQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.HistoryBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectBatchProviderTest {

	private DirectProviderUtil directProviderUtil;
	private ManagementService managementService;
	private HistoryService historyService;
	private DirectBatchProvider batchProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		managementService = mock(ManagementService.class);
		historyService = mock(HistoryService.class);
		when(processEngine.getManagementService()).thenReturn(managementService);
		when(processEngine.getHistoryService()).thenReturn(historyService);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		batchProvider = new DirectBatchProvider(directProviderUtil);
	}

	// ---------- delete ----------

	@Test
	void deleteBatch_doesNotCascadeByDefault() {
		batchProvider.deleteBatch("batch-1", new HashMap<>(), user);

		verify(managementService).deleteBatch("batch-1", false);
	}

	@Test
	void deleteBatch_cascadesWhenTheFlagIsTheStringTrue() {
		Map<String, Object> params = new HashMap<>();
		params.put("cascade", "true");

		batchProvider.deleteBatch("batch-1", params, user);

		verify(managementService).deleteBatch("batch-1", true);
	}

	@Test
	void deleteBatch_doesNotCascadeOnTheStringFalse() {
		Map<String, Object> params = new HashMap<>();
		params.put("cascade", "false");

		batchProvider.deleteBatch("batch-1", params, user);

		verify(managementService).deleteBatch("batch-1", false);
	}

	/**
	 * TODO KNOWN BUG (not fixed): {@code DirectBatchProvider.deleteBatch} compares the flag with
	 * {@code equals("true")}, so only the <em>String</em> {@code "true"} enables cascade. A JSON
	 * body sending a real boolean {@code true} - which is what a typed client sends - silently
	 * deletes without cascading. This test states the behaviour the method should have; it fails
	 * until the flag is coerced instead of string-compared.
	 */
	@Test
	@Disabled("KNOWN BUG: deleteBatch compares the cascade flag with equals(\"true\"), so a real JSON boolean never cascades")
	void deleteBatch_cascadesWhenTheFlagIsARealBoolean() {
		Map<String, Object> params = new HashMap<>();
		params.put("cascade", Boolean.TRUE);

		batchProvider.deleteBatch("batch-1", params, user);

		verify(managementService).deleteBatch("batch-1", true);
	}

	@Test
	void deleteBatch_wrapsABadRequestFromTheEngine() {
		doThrow(new BadUserRequestException("nope")).when(managementService).deleteBatch("batch-1", false);

		assertThatThrownBy(() -> batchProvider.deleteBatch("batch-1", new HashMap<>(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Unable to delete batch with id 'batch-1'");
	}

	// ---------- suspend / activate ----------

	@Test
	void setBatchSuspensionState_activatesWhenSuspendedIsAbsent() {
		batchProvider.setBatchSuspensionState("batch-1", new HashMap<>(), user);

		verify(managementService).activateBatchById("batch-1");
		verify(managementService, never()).suspendBatchById(Mockito.anyString());
	}

	@Test
	void setBatchSuspensionState_suspendsWhenAsked() {
		Map<String, Object> params = new HashMap<>();
		params.put("suspended", "true");

		batchProvider.setBatchSuspensionState("batch-1", params, user);

		verify(managementService).suspendBatchById("batch-1");
		verify(managementService, never()).activateBatchById(Mockito.anyString());
	}

	@Test
	void setBatchSuspensionState_wrapsABadRequestWhenSuspending() {
		Map<String, Object> params = new HashMap<>();
		params.put("suspended", "true");
		doThrow(new BadUserRequestException("nope")).when(managementService).suspendBatchById("batch-1");

		assertThatThrownBy(() -> batchProvider.setBatchSuspensionState("batch-1", params, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Unable to suspend batch with id 'batch-1'");
	}

	@Test
	void setBatchSuspensionState_wrapsABadRequestWhenActivating() {
		doThrow(new BadUserRequestException("nope")).when(managementService).activateBatchById("batch-1");

		assertThatThrownBy(() -> batchProvider.setBatchSuspensionState("batch-1", new HashMap<>(), user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Unable to activate batch with id 'batch-1'");
	}

	// ---------- historic batches ----------

	@Test
	void getHistoricBatchById_mapsTheHistoricBatch() {
		HistoricBatchQuery query = mock(HistoricBatchQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricBatchQuery()).thenReturn(query);
		HistoricBatch batch = mock(HistoricBatch.class);
		when(batch.getId()).thenReturn("batch-1");
		when(batch.getType()).thenReturn("instance-deletion");
		when(query.singleResult()).thenReturn(batch);

		HistoryBatch result = batchProvider.getHistoricBatchById("batch-1", user);

		assertThat(result.getId()).isEqualTo("batch-1");
		assertThat(result.getType()).isEqualTo("instance-deletion");
		verify(query).batchId("batch-1");
	}

	@Test
	void getHistoricBatchById_throwsWhenTheBatchIsUnknown() {
		HistoricBatchQuery query = mock(HistoricBatchQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricBatchQuery()).thenReturn(query);
		when(query.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> batchProvider.getHistoricBatchById("missing", user))
			.isInstanceOf(NoObjectFoundException.class);
	}

	@Test
	void getHistoricBatchCount_returnsTheQueryCount() {
		HistoricBatchQuery query = mock(HistoricBatchQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(historyService.createHistoricBatchQuery()).thenReturn(query);
		when(query.count()).thenReturn(5L);

		assertThat(batchProvider.getHistoricBatchCount(new HashMap<>(), user)).isEqualTo(5L);
	}

	@Test
	void deleteHistoricBatch_delegatesToTheHistoryService() {
		batchProvider.deleteHistoricBatch("batch-1", user);

		verify(historyService).deleteHistoricBatch("batch-1");
	}

	@Test
	void deleteHistoricBatch_wrapsABadRequestFromTheEngine() {
		doThrow(new BadUserRequestException("nope")).when(historyService).deleteHistoricBatch("batch-1");

		assertThatThrownBy(() -> batchProvider.deleteHistoricBatch("batch-1", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Unable to delete historic batch with id 'batch-1'");
	}
}
