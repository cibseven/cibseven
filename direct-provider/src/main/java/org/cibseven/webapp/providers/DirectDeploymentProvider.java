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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.exception.NotFoundException;
import org.cibseven.bpm.engine.exception.NotValidException;
import org.cibseven.bpm.engine.impl.calendar.DateTimeUtil;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.repository.DeploymentBuilder;
import org.cibseven.bpm.engine.repository.DeploymentQuery;
import org.cibseven.bpm.engine.repository.DeploymentWithDefinitions;
import org.cibseven.bpm.engine.repository.Resource;
import org.cibseven.bpm.engine.rest.dto.repository.DeploymentDto;
import org.cibseven.bpm.engine.rest.dto.repository.DeploymentQueryDto;
import org.cibseven.bpm.engine.rest.dto.repository.DeploymentResourceDto;
import org.cibseven.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.cibseven.bpm.engine.rest.dto.repository.RedeploymentDto;
import org.cibseven.bpm.engine.rest.util.QueryUtil;
import org.cibseven.webapp.Data;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoRessourcesFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.exception.WrongDeploymenIdException;
import org.cibseven.webapp.rest.model.Deployment;
import org.cibseven.webapp.rest.model.DeploymentResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

public class DirectDeploymentProvider implements IDeploymentProvider{

	DirectProviderUtil directProviderUtil;
	public DirectDeploymentProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	// Required in deployment code
	protected static final Map<String, String> MEDIA_TYPE_MAPPING = new HashMap<String, String>();

	static {
		MEDIA_TYPE_MAPPING.put("bpmn", MediaType.APPLICATION_XML.toString());
		MEDIA_TYPE_MAPPING.put("cmmn", MediaType.APPLICATION_XML.toString());
		MEDIA_TYPE_MAPPING.put("dmn", MediaType.APPLICATION_XML.toString());
		MEDIA_TYPE_MAPPING.put("json", MediaType.APPLICATION_JSON.toString());
		MEDIA_TYPE_MAPPING.put("xml", MediaType.APPLICATION_XML.toString());

		MEDIA_TYPE_MAPPING.put("gif", "image/gif");
		MEDIA_TYPE_MAPPING.put("jpeg", "image/jpeg");
		MEDIA_TYPE_MAPPING.put("jpe", "image/jpeg");
		MEDIA_TYPE_MAPPING.put("jpg", "image/jpeg");
		MEDIA_TYPE_MAPPING.put("png", "image/png");
		MEDIA_TYPE_MAPPING.put("svg", "image/svg+xml");
		MEDIA_TYPE_MAPPING.put("tiff", "image/tiff");
		MEDIA_TYPE_MAPPING.put("tif", "image/tiff");

		MEDIA_TYPE_MAPPING.put("groovy", "text/plain");
		MEDIA_TYPE_MAPPING.put("java", "text/plain");
		MEDIA_TYPE_MAPPING.put("js", "text/plain");
		MEDIA_TYPE_MAPPING.put("php", "text/plain");
		MEDIA_TYPE_MAPPING.put("py", "text/plain");
		MEDIA_TYPE_MAPPING.put("rb", "text/plain");

		MEDIA_TYPE_MAPPING.put("html", "text/html");
		MEDIA_TYPE_MAPPING.put("txt", "text/plain");
	}

public final static String DEPLOYMENT_NAME = "deployment-name";
public final static String DEPLOYMENT_ACTIVATION_TIME = "deployment-activation-time";
public final static String ENABLE_DUPLICATE_FILTERING = "enable-duplicate-filtering";
public final static String DEPLOY_CHANGED_ONLY = "deploy-changed-only";
public final static String DEPLOYMENT_SOURCE = "deployment-source";
public final static String TENANT_ID = "tenant-id";

	@Override
	public Deployment deployBpmn(MultiValueMap<String, Object> data, MultiValueMap<String, MultipartFile> multiFile,
			CIBUser user) throws SystemException {
		//SevenProvider only adds the first object of each file element to the request
		List<MultipartFile> fileList = new ArrayList<>();
		multiFile.forEach((key, value) -> { 
			try {
				fileList.add(value.get(0));
			} catch (Exception e) {
				throw new SystemException(e);
			}
		});
		DeploymentBuilder deploymentBuilder = extractDeploymentInformation(fileList.toArray(new MultipartFile[0]), data, user);

		if (!deploymentBuilder.getResourceNames().isEmpty()) {
			DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();

			DeploymentWithDefinitionsDto deploymentDto = DeploymentWithDefinitionsDto.fromDeployment(deployment);

			return directProviderUtil.convertValue(deploymentDto, Deployment.class, user);

		} else {
			throw new SystemException("No deployment resources contained in the form upload.");
		}
	}

	@Override
	public Long countDeployments(CIBUser user,
		MultiValueMap<String, String> queryParams) {

		// Convert MultiValueMap to MultivaluedMap for DeploymentQueryDto
		MultivaluedMap<String, String> queryParamsMap = new MultivaluedHashMap<>();
		if (queryParams != null) {
			queryParams.forEach((key, value) -> {
				if (value != null && !value.isEmpty()) {
					queryParamsMap.put(key, value);
				}
			});
		}

		DeploymentQueryDto queryDto = new DeploymentQueryDto(directProviderUtil.getObjectMapper(user), queryParamsMap);

		DeploymentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));

		return query.count();
	}

	@Override
	public Collection<Deployment> findDeployments(CIBUser user,
			MultiValueMap<String, String> queryParams,
		 	int firstResult, int maxResults,
			String sortBy, String sortOrder) {

		// Convert MultiValueMap to MultivaluedMap for DeploymentQueryDto
		MultivaluedMap<String, String> queryParamsMap = new MultivaluedHashMap<>();
		if (queryParams != null) {
			queryParams.forEach((key, value) -> {
				if (value != null && !value.isEmpty()) {
					queryParamsMap.put(key, value);
				}
			});
		}

		queryParamsMap.putSingle("sortBy", sortBy);
		queryParamsMap.putSingle("sortOrder", sortOrder);

		DeploymentQueryDto queryDto = new DeploymentQueryDto(directProviderUtil.getObjectMapper(user), queryParamsMap);
		DeploymentQuery query = queryDto.toQuery(directProviderUtil.getProcessEngine(user));
		List<org.cibseven.bpm.engine.repository.Deployment> matchingDeployments = QueryUtil.list(query, firstResult,
				maxResults);
		List<Deployment> deployments = new ArrayList<>();
		for (org.cibseven.bpm.engine.repository.Deployment deployment : matchingDeployments) {
			DeploymentDto def = DeploymentDto.fromDeployment(deployment);
			deployments.add(directProviderUtil.convertValue(def, Deployment.class, user));
		}
		return deployments;
	}

	@Override
	public Deployment findDeployment(String deploymentId, CIBUser user) {
		org.cibseven.bpm.engine.repository.Deployment deployment = directProviderUtil.getProcessEngine(user).getRepositoryService().createDeploymentQuery()
				.deploymentId(deploymentId).singleResult();
		if (deployment == null) {
			throw new WrongDeploymenIdException(new SystemException("Deployment with id '" + deploymentId + "' does not exist"));
		}

		return directProviderUtil.convertValue(DeploymentDto.fromDeployment(deployment), Deployment.class, user);
	}

	@Override
	public Collection<DeploymentResource> findDeploymentResources(String deploymentId, CIBUser user) {
		List<Resource> resources = directProviderUtil.getProcessEngine(user).getRepositoryService().getDeploymentResources(deploymentId);

		List<DeploymentResource> deploymentResources = new ArrayList<DeploymentResource>();
		for (Resource resource : resources) {
			deploymentResources.add(directProviderUtil.convertValue(DeploymentResourceDto.fromResources(resource), DeploymentResource.class, user));
		}

		if (!deploymentResources.isEmpty()) {
			return deploymentResources;
		} else {
			throw new NoRessourcesFoundException(new SystemException("Deployment resources for deployment id '" + deploymentId + "' do not exist."));
		}
	}

	@Override
	public Data fetchDataFromDeploymentResource(HttpServletRequest rq, String deploymentId, String resourceId,
			String fileName, CIBUser user) {
		InputStream resourceAsStream = directProviderUtil.getProcessEngine(user).getRepositoryService().getResourceAsStreamById(deploymentId, resourceId);
		if (resourceAsStream != null) {
			DeploymentResourceDto resource = getDeploymentResource(resourceId, deploymentId, user);
			String name = resource.getName();
			String filename = null;
			String mediaType = null;

			if (name != null) {
				name = name.replace("\\", "/");
				String[] filenameParts = name.split("/");
				if (filenameParts.length > 0) {
					int idx = filenameParts.length - 1;
					filename = filenameParts[idx];
				}

				String[] extensionParts = name.split("\\.");
				if (extensionParts.length > 0) {
					int idx = extensionParts.length - 1;
					String extension = extensionParts[idx];
					if (extension != null) {
						mediaType = MEDIA_TYPE_MAPPING.get(extension);
					}
				}
			}

			if (filename == null) {
				filename = "data";
			}

			if (mediaType == null) {
				mediaType = MediaType.APPLICATION_OCTET_STREAM.toString();
			}

			try {
				byte[] body = resourceAsStream.readAllBytes();
				if (body == null)
					throw new NullPointerException();
				InputStream targetStream = new ByteArrayInputStream(body);
				InputStreamSource iso = new InputStreamResource(targetStream);
				Data returnValue = new Data(fileName, mediaType, iso, body.length);
				return returnValue;
			} catch (IOException e) {
				throw new SystemException(
						"Deployment resource '" + resourceId + "' for deployment id '" + deploymentId + "'could not be read.");
			} finally {
				IoUtil.closeSilently(resourceAsStream);
			}
		} else {
			throw new SystemException(
					"Deployment resource '" + resourceId + "' for deployment id '" + deploymentId + "' does not exist.");
		}
	}

	@Override
	public void deleteDeployment(String deploymentId, Boolean cascade, CIBUser user) throws SystemException {
		org.cibseven.bpm.engine.repository.Deployment deployment = directProviderUtil.getProcessEngine(user).getRepositoryService().createDeploymentQuery()
				.deploymentId(deploymentId).singleResult();
		if (deployment == null) {
			throw new WrongDeploymenIdException(new SystemException("Deployment with id '" + deploymentId + "' do not exist"));
		}

		directProviderUtil.getProcessEngine(user).getRepositoryService().deleteDeployment(deploymentId, cascade, false, false);
	}

	@Override
	public Deployment createDeployment(MultiValueMap<String, Object> data, MultipartFile[] files, CIBUser user)
			throws SystemException {
		DeploymentBuilder deploymentBuilder = extractDeploymentInformation(files, data, user);

		if (!deploymentBuilder.getResourceNames().isEmpty()) {
			DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();
			DeploymentWithDefinitionsDto deploymentDto = DeploymentWithDefinitionsDto.fromDeployment(deployment);
			return directProviderUtil.convertValue(deploymentDto, Deployment.class, user);

		} else {
			throw new SystemException("No deployment resources contained in the form upload.");
		}
	}

	@Override
	public Deployment redeployDeployment(String deploymentId, Map<String, Object> data, CIBUser user) throws SystemException {
		RedeploymentDto redeployment = directProviderUtil.convertValue(data, RedeploymentDto.class, user);
    DeploymentWithDefinitions deployment = null;
    try {
      deployment = tryToRedeploy(deploymentId, redeployment, user);

    } catch (NotValidException|NotFoundException e) {
      throw new SystemException(e.getMessage(), e);
    }

    DeploymentWithDefinitionsDto deploymentDto = DeploymentWithDefinitionsDto.fromDeployment(deployment);

    return directProviderUtil.convertValue(deploymentDto, Deployment.class, user);
	}

  private DeploymentWithDefinitions tryToRedeploy(String deploymentId, RedeploymentDto redeployment, CIBUser user) {
    ProcessEngine processEngine = directProviderUtil.getProcessEngine(user);
  	DeploymentBuilder builder = processEngine.getRepositoryService().createDeployment();
    builder.nameFromDeployment(deploymentId);

    org.cibseven.bpm.engine.repository.Deployment deployment = processEngine.getRepositoryService().createDeploymentQuery().deploymentId(deploymentId).singleResult();
	   if (deployment == null) {
	     throw new WrongDeploymenIdException(new SystemException("Deployment with id '" + deploymentId + "' does not exist"));
	  }
	  String tenantId = deployment.getTenantId();
    if (tenantId != null) {
      builder.tenantId(tenantId);
    }

    if (redeployment != null) {
      builder = addRedeploymentResources(deploymentId, builder, redeployment);
    } else {
      builder.addDeploymentResources(deploymentId);
    }

    return builder.deployWithResult();
  }

	private DeploymentBuilder extractDeploymentInformation(MultipartFile[] files, MultiValueMap<String, Object> data, CIBUser user) {
		DeploymentBuilder deploymentBuilder = directProviderUtil.getProcessEngine(user).getRepositoryService().createDeployment();

		for (MultipartFile file : files) {
			String fileName = file.getOriginalFilename();
			if (fileName != null) {
				try {
					deploymentBuilder.addInputStream(fileName, new ByteArrayInputStream(file.getBytes()));
				} catch (IOException e) {
					throw new SystemException(e.getMessage(), e);
				}
			} else {
				throw new SystemException(
						"No file name found in the deployment resource described by form parameter '" + fileName + "'.");
			}
		}
		String deploymentName = getStringValue(DEPLOYMENT_NAME, data);
		if (deploymentName != null) {
			deploymentBuilder.name(deploymentName);
		}

		String deploymentActivationTime = getStringValue(DEPLOYMENT_ACTIVATION_TIME, data);
		if (deploymentActivationTime != null) {
			deploymentBuilder.activateProcessDefinitionsOn(DateTimeUtil.parseDate(deploymentActivationTime));
		}

		String deploymentSource = getStringValue(DEPLOYMENT_SOURCE, data);
		if (deploymentSource != null) {
			deploymentBuilder.source(deploymentSource);
		}

		String deploymentTenantId = getStringValue(TENANT_ID, data);
		if (deploymentTenantId != null) {
			deploymentBuilder.tenantId(deploymentTenantId);
		}

		extractDuplicateFilteringForDeployment(data, deploymentBuilder);
		return deploymentBuilder;
	}

	public DeploymentResourceDto getDeploymentResource(String resourceId, String deploymentId, CIBUser user) {
		RepositoryService repositoryService = directProviderUtil.getProcessEngine(user).getRepositoryService();
		List<Resource> resources = repositoryService.getDeploymentResources(deploymentId);
		List<DeploymentResourceDto> deploymentResources = new ArrayList<DeploymentResourceDto>();
		for (Resource resource : resources) {
			deploymentResources.add(DeploymentResourceDto.fromResources(resource));
		}

		if (deploymentResources.isEmpty()) {
			throw new NoRessourcesFoundException(new SystemException("Deployment resources for deployment id '" + deploymentId + "' do not exist."));
		}
		for (DeploymentResourceDto deploymentResource : deploymentResources) {
			if (deploymentResource.getId().equals(resourceId)) {
				return deploymentResource;
			}
		}

		throw new SystemException("Deployment resource with resource id '" + resourceId + "' for deployment id '"
				+ deploymentId + "' does not exist.");
	}

  private DeploymentBuilder addRedeploymentResources(String deploymentId, DeploymentBuilder builder, RedeploymentDto redeployment) {
    builder.source(redeployment.getSource());

    List<String> resourceIds = redeployment.getResourceIds();
    List<String> resourceNames = redeployment.getResourceNames();

    boolean isResourceIdListEmpty = resourceIds == null || resourceIds.isEmpty();
    boolean isResourceNameListEmpty = resourceNames == null || resourceNames.isEmpty();

    if (isResourceIdListEmpty && isResourceNameListEmpty) {
      builder.addDeploymentResources(deploymentId);

    } else {
      if (!isResourceIdListEmpty) {
        builder.addDeploymentResourcesById(deploymentId, resourceIds);
      }
      if (!isResourceNameListEmpty) {
        builder.addDeploymentResourcesByName(deploymentId, resourceNames);
      }
    }
    return builder;
  }

	private void extractDuplicateFilteringForDeployment(MultiValueMap<String, Object> data, DeploymentBuilder deploymentBuilder) {
		boolean enableDuplicateFiltering = false;
		boolean deployChangedOnly = false;

		String enableDuplicateFilteringValue = getStringValue(ENABLE_DUPLICATE_FILTERING, data);
		if (enableDuplicateFilteringValue != null)
			enableDuplicateFiltering = Boolean.parseBoolean(enableDuplicateFilteringValue);

		String deployChangedOnlyValue = getStringValue(DEPLOY_CHANGED_ONLY, data);
		if (deployChangedOnlyValue != null)
			deployChangedOnly = Boolean.parseBoolean(deployChangedOnlyValue);

		// deployChangedOnly overrides the enableDuplicateFiltering setting
		if (deployChangedOnly) {
			deploymentBuilder.enableDuplicateFiltering(true);
		} else if (enableDuplicateFiltering) {
			deploymentBuilder.enableDuplicateFiltering(false);
		}
	}

	private String getStringValue(String key, MultiValueMap<String, Object> data) {
		if (data.containsKey(key)) {
			List<Object> entryData = data.get(key);
			if (!entryData.isEmpty() && entryData.get(0) instanceof String)
				return (String)entryData.get(0);
		}
		return null;
	}
}
