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

import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import lombok.Getter;

@Getter
public class SevenDirectProvider implements BpmProvider {
    //decides about ldap/adfs
	@Value("${cibseven.webclient.user.provider:org.cibseven.webapp.auth.SevenUserProvider}") String sevenUserProvider;
	@Value("${cibseven.webclient.users.search.wildcard:}") String wildcard;
	
	// base project providers
	private ITaskProvider taskProvider = null;
	private IProcessProvider processProvider = null;
	private IFilterProvider filterProvider = null;
	private IDeploymentProvider deploymentProvider = null;
	private IVariableProvider variableProvider = null;
	private IActivityProvider activityProvider = null;
	private IIncidentProvider incidentProvider = null;
	private IUserProvider userProvider;
	private IVariableInstanceProvider variableInstanceProvider = null;
	private IUtilsProvider utilsProvider = null;
	private IHistoricVariableInstanceProvider historicVariableInstanceProvider = null;
	private IExternalTaskProvider externalTaskProvider = null;
	private IDecisionProvider decisionProvider = null;
	private IJobProvider jobProvider = null;
	private IJobDefinitionProvider jobDefinitionProvider = null;
	private IEngineProvider engineProvider = null;
	private IBatchProvider batchProvider = null;
	private ITenantProvider tenantProvider = null;
	private ISystemProvider systemProvider = null;
	private IIdentityProvider identityProvider = null;

	DirectProviderUtil directProviderUtil = null;

	/**
	 * Wraps a Direct*Provider so the engine enforces its authorizations for every call carrying a CIBUser.
	 * @see AuthorizingProviderProxy
	 */
	protected <T> T authorizing(T provider, Class<T> iface) {
		return AuthorizingProviderProxy.wrap(provider, iface, directProviderUtil);
	}

	@PostConstruct
  	public void init() {
  		directProviderUtil = new DirectProviderUtil();
		engineProvider = authorizing(new DirectEngineProvider(getDirectProviderUtil()), IEngineProvider.class);
		directProviderUtil.setEngineProvider(engineProvider);
		deploymentProvider = authorizing(new DirectDeploymentProvider(getDirectProviderUtil()), IDeploymentProvider.class);
		variableProvider = authorizing(new DirectVariableProvider(getDirectProviderUtil()), IVariableProvider.class);
		variableInstanceProvider = authorizing(new DirectVariableInstanceProvider(getDirectProviderUtil()), IVariableInstanceProvider.class);
		historicVariableInstanceProvider = authorizing(new DirectHistoricVariableInstanceProvider(getDirectProviderUtil()), IHistoricVariableInstanceProvider.class);
		taskProvider = authorizing(new DirectTaskProvider(getDirectProviderUtil()), ITaskProvider.class);
		processProvider = authorizing(new DirectProcessProvider(getDirectProviderUtil(), this), IProcessProvider.class);
		activityProvider = authorizing(new DirectActivityProvider(getDirectProviderUtil()), IActivityProvider.class);
		filterProvider = authorizing(new DirectFilterProvider(getDirectProviderUtil()), IFilterProvider.class);
		utilsProvider = authorizing(new DirectUtilsProvider(getDirectProviderUtil()), IUtilsProvider.class);
		incidentProvider = authorizing(new DirectIncidentProvider(getDirectProviderUtil()), IIncidentProvider.class);
		jobDefinitionProvider = authorizing(new DirectJobDefinitionProvider(getDirectProviderUtil()), IJobDefinitionProvider.class);
		userProvider = authorizing(new DirectUserProvider(getDirectProviderUtil(), sevenUserProvider, wildcard), IUserProvider.class);
		decisionProvider = authorizing(new DirectDecisionProvider(getDirectProviderUtil()), IDecisionProvider.class);
		jobProvider = authorizing(new DirectJobProvider(getDirectProviderUtil()), IJobProvider.class);
		batchProvider = authorizing(new DirectBatchProvider(getDirectProviderUtil()), IBatchProvider.class);
		systemProvider = authorizing(new DirectSystemProvider(getDirectProviderUtil()), ISystemProvider.class);
		tenantProvider = authorizing(new DirectTenantProvider(getDirectProviderUtil()), ITenantProvider.class);
		externalTaskProvider = authorizing(new DirectExternalTaskProvider(getDirectProviderUtil()), IExternalTaskProvider.class);
		identityProvider = authorizing(new DirectIdentityProvider(getDirectProviderUtil()), IIdentityProvider.class);
  	}
}
