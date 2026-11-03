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
import java.util.List;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.repository.DeploymentQuery;
import org.cibseven.bpm.engine.repository.Resource;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoRessourcesFoundException;
import org.cibseven.webapp.exception.WrongDeploymenIdException;
import org.cibseven.webapp.rest.model.Deployment;
import org.cibseven.webapp.rest.model.DeploymentResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DirectDeploymentProviderTest {

	private DirectProviderUtil directProviderUtil;
	private RepositoryService repositoryService;
	private DeploymentQuery deploymentQuery;
	private DirectDeploymentProvider deploymentProvider;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");

		ProcessEngine processEngine = mock(ProcessEngine.class);
		repositoryService = mock(RepositoryService.class);
		when(processEngine.getRepositoryService()).thenReturn(repositoryService);

		deploymentQuery = mock(DeploymentQuery.class, withSettings().defaultAnswer(RETURNS_SELF));
		when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);

		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);

		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(processEngine).when(directProviderUtil).getProcessEngine(any(CIBUser.class));
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));

		deploymentProvider = new DirectDeploymentProvider(directProviderUtil);
	}

	private org.cibseven.bpm.engine.repository.Deployment mockEngineDeployment(String id, String name) {
		org.cibseven.bpm.engine.repository.Deployment deployment =
			mock(org.cibseven.bpm.engine.repository.Deployment.class);
		when(deployment.getId()).thenReturn(id);
		when(deployment.getName()).thenReturn(name);
		return deployment;
	}

	@Test
	void findDeployment_mapsTheEngineDeployment() {
		org.cibseven.bpm.engine.repository.Deployment engineDeployment =
			mockEngineDeployment("deployment-1", "invoice.bpmn");
		when(deploymentQuery.singleResult()).thenReturn(engineDeployment);

		Deployment deployment = deploymentProvider.findDeployment("deployment-1", user);

		assertThat(deployment.getId()).isEqualTo("deployment-1");
		assertThat(deployment.getName()).isEqualTo("invoice.bpmn");
		verify(deploymentQuery).deploymentId("deployment-1");
	}

	@Test
	void findDeployment_throwsWhenTheDeploymentIsUnknown() {
		when(deploymentQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> deploymentProvider.findDeployment("missing", user))
			.isInstanceOf(WrongDeploymenIdException.class);
	}

	@Test
	void findDeploymentResources_mapsEveryResource() {
		Resource first = mockResource("resource-1", "invoice.bpmn");
		Resource second = mockResource("resource-2", "invoice.dmn");
		when(repositoryService.getDeploymentResources("deployment-1")).thenReturn(List.of(first, second));

		Collection<DeploymentResource> resources =
			deploymentProvider.findDeploymentResources("deployment-1", user);

		assertThat(resources).extracting(DeploymentResource::getName)
			.containsExactly("invoice.bpmn", "invoice.dmn");
	}

	@Test
	void findDeploymentResources_throwsWhenTheDeploymentHasNone() {
		when(repositoryService.getDeploymentResources("deployment-1")).thenReturn(List.of());

		// an empty deployment is reported as "no resources", not as an empty list
		assertThatThrownBy(() -> deploymentProvider.findDeploymentResources("deployment-1", user))
			.isInstanceOf(NoRessourcesFoundException.class);
	}

	private Resource mockResource(String id, String name) {
		Resource resource = mock(Resource.class);
		when(resource.getId()).thenReturn(id);
		when(resource.getName()).thenReturn(name);
		return resource;
	}

	@Test
	void deleteDeployment_cascadesWhenAsked() {
		org.cibseven.bpm.engine.repository.Deployment engineDeployment =
			mockEngineDeployment("deployment-1", "invoice.bpmn");
		when(deploymentQuery.singleResult()).thenReturn(engineDeployment);

		deploymentProvider.deleteDeployment("deployment-1", Boolean.TRUE, user);

		// cascade deletes the running instances too; the other two flags stay off
		verify(repositoryService).deleteDeployment("deployment-1", Boolean.TRUE, false, false);
	}

	@Test
	void deleteDeployment_refusesToDeleteWhatDoesNotExist() {
		when(deploymentQuery.singleResult()).thenReturn(null);

		assertThatThrownBy(() -> deploymentProvider.deleteDeployment("missing", Boolean.FALSE, user))
			.isInstanceOf(WrongDeploymenIdException.class);
		verify(repositoryService, never()).deleteDeployment(
			Mockito.anyString(), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
	}
}
