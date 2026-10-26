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
import java.util.Set;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.engine.impl.util.ParseUtil;
import org.cibseven.bpm.engine.rest.dto.identity.UserCredentialsDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserDto;
import org.cibseven.bpm.engine.rest.dto.identity.UserProfileDto;
import org.cibseven.bpm.engine.rest.exception.RestException;
import org.cibseven.bpm.engine.rest.impl.SetupRestServiceImpl;
import org.cibseven.bpm.engine.rest.util.EngineUtil;
import org.cibseven.webapp.exception.InvalidUserIdException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.Engine;
import org.cibseven.webapp.rest.model.EngineConfiguration;
import org.cibseven.webapp.rest.model.NewUser;
import org.springframework.lang.Nullable;

public class DirectEngineProvider implements IEngineProvider {

	DirectProviderUtil directProviderUtil;

	private String effectiveDefaultEngineId = null;

	DirectEngineProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}

	@Override
	public Collection<Engine> getProcessEngineDefinitions() {
		Set<String> engineNames = null;
		// either one of the two methods can be used to lookup the process engine - the other might fail for unknown reasons
		try {
			engineNames = EngineUtil.getProcessEngineProvider().getProcessEngineNames();
			if (engineNames == null || engineNames.isEmpty()) {
				engineNames = BpmPlatform.getProcessEngineService().getProcessEngineNames();
			}
		} catch (RestException ex) {
			engineNames = BpmPlatform.getProcessEngineService().getProcessEngineNames();
		} finally {
			if (engineNames == null || engineNames.isEmpty())
				throw new SystemException("No process engine found.");
		}
		List<Engine> results = new ArrayList<>();
		for (String engineName : engineNames) {
			Engine engine = new Engine(engineName);
			engine.setId(engineName);
			results.add(engine);
		}

		return results;
	}

	@Override
	@Nullable
	public EngineConfiguration getEngineConfiguration(String engineId) {
		// An unspecified engine resolves to the engine named "default", matching the HTTP provider.
		engineId = IEngineProvider.isEngineUnspecified(engineId) ? IEngineProvider.ENGINE_NAME_DEFAULT : engineId;
		org.cibseven.bpm.engine.ProcessEngine processEngine = directProviderUtil.getProcessEngine(engineId);
		if (processEngine == null) {
			return null;
		}
		org.cibseven.bpm.engine.ProcessEngineConfiguration config = processEngine.getProcessEngineConfiguration();
		if (config == null) {
			return null;
		}
		EngineConfiguration result = new EngineConfiguration();
		result.setEngineName(processEngine.getName());
		result.setHistoryLevel(config.getHistory());
		result.setAuthorizationEnabled(config.isAuthorizationEnabled());
		result.setEnablePasswordPolicy(config.isEnablePasswordPolicy());
		// getHistoryTimeToLive()/isEnforceHistoryTimeToLive() are declared on the impl class,
		// not the public ProcessEngineConfiguration interface.
		// For any other (custom) implementation the real values cannot be read, so both fields are
		// left null ("unknown") - the same as an engine-rest before 3.0.0 that does not report them -
		// instead of guessing the engine defaults.
		if (config instanceof org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl impl) {
			// Same representation as the engine-rest /configuration endpoint (3.0.0+):
			// "180" / "P180D" -> 180, "0" / "P0D" -> 0, null / "" (no default configured) -> -1.
			// The engine validates the value with the same method at startup, so it cannot throw here.
			Integer historyTimeToLive = ParseUtil.parseHistoryTimeToLive(impl.getHistoryTimeToLive());
			result.setHistoryTimeToLive(historyTimeToLive != null ? historyTimeToLive : -1);
			result.setEnforceHistoryTimeToLive(impl.isEnforceHistoryTimeToLive());
		}
		return result;
	}

	@Override
	public String getEffectiveDefaultEngineId() {
		if (effectiveDefaultEngineId == null) {
			effectiveDefaultEngineId = IEngineProvider.super.getEffectiveDefaultEngineId();
		}
		return effectiveDefaultEngineId;
	}

	@Override
	public Boolean requiresSetup(String engineId) {
		return new SetupRestServiceImpl(engineId, directProviderUtil.getObjectMapper(engineId)).requiresSetup();
	}
	
	@Override
	public void createSetupUser(NewUser user, String engineId) throws InvalidUserIdException {
		UserDto userDto = new UserDto();
		UserProfileDto profileDto = new UserProfileDto();
		if (user.getProfile() == null || user.getCredentials() == null) 
			throw new SystemException("User data not provided."); 
		profileDto.setEmail(user.getProfile().getEmail());
		profileDto.setFirstName(user.getProfile().getFirstName());
		profileDto.setId(user.getProfile().getId());
		profileDto.setLastName(user.getProfile().getLastName());
		userDto.setProfile(profileDto);
		UserCredentialsDto userCredentialsDto = new UserCredentialsDto();
		userCredentialsDto.setPassword(user.getCredentials().getPassword());
		userDto.setCredentials(userCredentialsDto);
		new SetupRestServiceImpl(engineId, directProviderUtil.getObjectMapper(engineId)).createUser(userDto);
	}

}
