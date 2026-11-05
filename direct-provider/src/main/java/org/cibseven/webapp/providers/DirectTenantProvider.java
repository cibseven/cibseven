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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.cibseven.bpm.engine.rest.dto.identity.TenantDto;
import org.cibseven.bpm.engine.rest.dto.identity.TenantQueryDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Tenant;

public class DirectTenantProvider implements ITenantProvider {

	DirectProviderUtil directProviderUtil;

	DirectTenantProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Tenant> fetchTenants(Map<String, Object> queryParams, CIBUser user) {
		TenantQueryDto queryDto = directProviderUtil.getObjectMapper(user).convertValue(queryParams, TenantQueryDto.class);

		TenantQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.identity.Tenant> tenants = QueryUtil.list(query, null, null);
		List<Tenant> tenantList = new ArrayList<>();
		List<TenantDto> tennantDtoList = TenantDto.fromTenantList(tenants);
		for (TenantDto tennantDto : tennantDtoList) {
			tenantList.add(directProviderUtil.convertValue(tennantDto, Tenant.class, user));
		}
		return tenantList;
	}

	@Override
	public Tenant fetchTenant(String tenantId, CIBUser user) {
		org.cibseven.bpm.engine.identity.Tenant tenant = findTenantObject(tenantId, user);
		TenantDto dto = TenantDto.fromTenant(tenant);
		return directProviderUtil.convertValue(dto, Tenant.class, user);
	}

	@Override
	public void createTenant(Tenant tenant, CIBUser user) {
		ensureNotReadOnly(user);
		TenantDto tenantDto = directProviderUtil.convertValue(tenant, TenantDto.class, user);

		org.cibseven.bpm.engine.identity.Tenant newTenant = directProviderUtil.getProcessEngine(user).getIdentityService().newTenant(tenantDto.getId());
		tenantDto.update(newTenant);

		directProviderUtil.getProcessEngine(user).getIdentityService().saveTenant(newTenant);
	}

	@Override
	public void updateTenant(Tenant tenant, CIBUser user) {
		ensureNotReadOnly(user);
		TenantDto tenantDto = directProviderUtil.convertValue(tenant, TenantDto.class, user);
		org.cibseven.bpm.engine.identity.Tenant systemTenant = findTenantObject(tenant.getId(), user);
		if (systemTenant == null) {
			throw new NoObjectFoundException(new SystemException("Tenant with id " + tenant.getId() + " does not exist"));
		}
		tenantDto.update(systemTenant);
		directProviderUtil.getProcessEngine(user).getIdentityService().saveTenant(systemTenant);
	}

	@Override
	public void deleteTenant(String tenantId, CIBUser user) {
		ensureNotReadOnly(user);
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteTenant(tenantId);
	}

	@Override
	public void addMemberToTenant(String tenantId, String userId, CIBUser user) {
		ensureNotReadOnly(user);
		directProviderUtil.getProcessEngine(user).getIdentityService().createTenantUserMembership(tenantId, userId);
	}

	@Override
	public void deleteMemberFromTenant(String tenantId, String userId, CIBUser user) {
		ensureNotReadOnly(user);
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteTenantUserMembership(tenantId, userId);
	}

	@Override
	public void addGroupToTenant(String tenantId, String groupId, CIBUser user) {
		ensureNotReadOnly(user);
		directProviderUtil.getProcessEngine(user).getIdentityService().createTenantGroupMembership(tenantId, groupId);
	}

	@Override
	public void deleteGroupFromTenant(String tenantId, String groupId, CIBUser user) {
		ensureNotReadOnly(user);
		directProviderUtil.getProcessEngine(user).getIdentityService().deleteTenantGroupMembership(tenantId, groupId);
	}

	private void ensureNotReadOnly(CIBUser user) {
		if (directProviderUtil.getProcessEngine(user).getIdentityService().isReadOnly()) {
			throw new SystemException("Identity service implementation is read-only.");
		}
	}

	private org.cibseven.bpm.engine.identity.Tenant findTenantObject(String tenantId, CIBUser user) {
		try {
			return directProviderUtil.getProcessEngine(user).getIdentityService().createTenantQuery().tenantId(tenantId).singleResult();

		} catch (ProcessEngineException e) {
			throw new SystemException("Exception while performing tenant query: " + e.getMessage());
		}
	}

}
