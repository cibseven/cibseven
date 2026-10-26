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

import org.cibseven.bpm.engine.rest.dto.runtime.VariableInstanceDto;
import org.cibseven.bpm.engine.runtime.VariableInstanceQuery;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.NoObjectFoundException;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.VariableInstance;

public class DirectVariableInstanceProvider implements IVariableInstanceProvider {

	DirectProviderUtil directProviderUtil;
	public DirectVariableInstanceProvider(DirectProviderUtil directProviderUtil){
		this.directProviderUtil = directProviderUtil;
	}


	@Override
	public VariableInstance getVariableInstance(String id, boolean deserializeValue, CIBUser user)
			throws SystemException, NoObjectFoundException {
		VariableInstance variableDeserialized = getVariableInstanceImpl(id, true, user);
		VariableInstance variableSerialized = getVariableInstanceImpl(id, false, user);
		if (variableDeserialized == null || variableSerialized == null)
			throw new SystemException("Variable not found: " + id);

		if (deserializeValue) {
			variableDeserialized.setValueSerialized(variableSerialized.getValue());
			variableDeserialized.setValueDeserialized(variableDeserialized.getValue());
			return variableDeserialized;
		} else {
			variableSerialized.setValueSerialized(variableSerialized.getValue());
			variableSerialized.setValueDeserialized(variableDeserialized.getValue());
			return variableSerialized;
		}
	}

	private VariableInstance getVariableInstanceImpl(String id, boolean deserializeValue, CIBUser user)
			throws SystemException, NoObjectFoundException {
		VariableInstanceQuery variableInstanceQuery = directProviderUtil.getProcessEngine(user).getRuntimeService().createVariableInstanceQuery().variableId(id);
		// do not fetch byte arrays
		variableInstanceQuery.disableBinaryFetching();

		if (!deserializeValue) {
			variableInstanceQuery.disableCustomObjectDeserialization();
		}
		org.cibseven.bpm.engine.runtime.VariableInstance variableEngineInstance = variableInstanceQuery.singleResult();
		if (variableEngineInstance != null) {
			VariableInstanceDto instanceDto = VariableInstanceDto.fromVariableInstance(variableEngineInstance);
			VariableInstance variableInstance = directProviderUtil.convertValue(instanceDto, VariableInstance.class, user);
			return variableInstance;
		} else {
			throw new NoObjectFoundException(new SystemException("Variable with Id '" + id + "' does not exist."));
		}
	}

}
