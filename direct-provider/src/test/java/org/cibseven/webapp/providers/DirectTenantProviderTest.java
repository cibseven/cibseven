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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collection;
import java.util.HashMap;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectTenantProviderTest {

	private DirectProviderUtil directProviderUtil;
	private IdentityService identityService;
	private TenantQuery tenantQuery;
	private DirectTenantProvider tenantProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		identityService = mock(IdentityService.class);
		when(processEngine.getIdentityService()).thenReturn(identityService);

		tenantQuery = mock(TenantQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(identityService.createTenantQuery()).thenReturn(tenantQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		tenantProvider = new DirectTenantProvider(directProviderUtil);
	}

	private org.cibseven.bpm.engine.identity.Tenant mockEngineTenant(String id, String name) {
		org.cibseven.bpm.engine.identity.Tenant tenant = mock(org.cibseven.bpm.engine.identity.Tenant.class);
		when(tenant.getId()).thenReturn(id);
		when(tenant.getName()).thenReturn(name);
		return tenant;
	}

	@Test
	void fetchTenants_mapsEveryTenant() {
		org.cibseven.bpm.engine.identity.Tenant engineTenant = mockEngineTenant("tenant-a", "Tenant A");
		when(tenantQuery.list()).thenReturn(java.util.List.of(engineTenant));

		Collection<Tenant> tenants = tenantProvider.fetchTenants(new HashMap<>(), user);

		assertThat(tenants).hasSize(1);
		assertThat(tenants.iterator().next().getName()).isEqualTo("Tenant A");
	}

	@Test
	void fetchTenant_mapsTheSingleTenant() {
		org.cibseven.bpm.engine.identity.Tenant engineTenant = mockEngineTenant("tenant-a", "Tenant A");
		when(tenantQuery.singleResult()).thenReturn(engineTenant);

		Tenant tenant = tenantProvider.fetchTenant("tenant-a", user);

		assertThat(tenant.getId()).isEqualTo("tenant-a");
		verify(tenantQuery).tenantId("tenant-a");
	}

	@Test
	void fetchTenant_wrapsAFailingTenantQuery() {
		when(identityService.createTenantQuery()).thenThrow(new ProcessEngineException("db down"));

		assertThatThrownBy(() -> tenantProvider.fetchTenant("tenant-a", user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("Exception while performing tenant query");
	}

	@Test
	void createTenant_savesANewEngineTenant() {
		org.cibseven.bpm.engine.identity.Tenant newTenant = mockEngineTenant("tenant-a", null);
		when(identityService.newTenant("tenant-a")).thenReturn(newTenant);
		Tenant tenant = new Tenant();
		tenant.setId("tenant-a");
		tenant.setName("Tenant A");

		tenantProvider.createTenant(tenant, user);

		verify(identityService).saveTenant(newTenant);
	}

	@Test
	void createTenant_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);
		Tenant tenant = new Tenant();
		tenant.setId("tenant-a");

		// e.g. an LDAP-backed identity service - writing would fail deeper down anyway
		assertThatThrownBy(() -> tenantProvider.createTenant(tenant, user))
			.isInstanceOf(SystemException.class)
			.hasMessageContaining("read-only");
		verify(identityService, never()).saveTenant(any());
	}

	@Test
	void updateTenant_savesOverTheExistingTenant() {
		org.cibseven.bpm.engine.identity.Tenant existing = mockEngineTenant("tenant-a", "old");
		when(tenantQuery.singleResult()).thenReturn(existing);
		Tenant tenant = new Tenant();
		tenant.setId("tenant-a");
		tenant.setName("new");

		tenantProvider.updateTenant(tenant, user);

		verify(existing).setName("new");
		verify(identityService).saveTenant(existing);
	}

	@Test
	void updateTenant_throwsWhenTheTenantIsGone() {
		when(tenantQuery.singleResult()).thenReturn(null);
		Tenant tenant = new Tenant();
		tenant.setId("missing");

		assertThatThrownBy(() -> tenantProvider.updateTenant(tenant, user))
			.isInstanceOf(NoObjectFoundException.class);
		verify(identityService, never()).saveTenant(any());
	}

	@Test
	void deleteTenant_delegatesToTheIdentityService() {
		tenantProvider.deleteTenant("tenant-a", user);

		verify(identityService).deleteTenant("tenant-a");
	}

	@Test
	void deleteTenant_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> tenantProvider.deleteTenant("tenant-a", user))
			.isInstanceOf(SystemException.class);
		verify(identityService, never()).deleteTenant(Mockito.anyString());
	}

	@Test
	void addMemberToTenant_createsTheMembership() {
		tenantProvider.addMemberToTenant("tenant-a", "demo", user);

		verify(identityService).createTenantUserMembership("tenant-a", "demo");
	}

	@Test
	void deleteMemberFromTenant_removesTheMembership() {
		tenantProvider.deleteMemberFromTenant("tenant-a", "demo", user);

		verify(identityService).deleteTenantUserMembership("tenant-a", "demo");
	}

	@Test
	void addMemberToTenant_refusesWhenTheIdentityServiceIsReadOnly() {
		when(identityService.isReadOnly()).thenReturn(true);

		assertThatThrownBy(() -> tenantProvider.addMemberToTenant("tenant-a", "demo", user))
			.isInstanceOf(SystemException.class);
		verify(identityService, never()).createTenantUserMembership(Mockito.anyString(), Mockito.anyString());
	}
}
