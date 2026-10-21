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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.rest.dto.VariableValueDto;
import org.cibseven.bpm.engine.rest.mapper.JacksonConfigurator;
import org.cibseven.common.api.auth.CIBUser;
import org.cibseven.common.api.rest.model.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link DirectProviderUtil#toVariableValueDto} has to make the same adjustments to a submitted form
 * variable that {@code VariableProvider.submitVariables} makes before posting to engine-rest (CIB7-880).
 */
public class DirectProviderUtilVariableValueTest {

	private DirectProviderUtil directProviderUtil;
	private CIBUser user;

	@BeforeEach
	void setUp() {
		user = new CIBUser("testUser");
		ObjectMapper objectMapper = new ObjectMapper();
		JacksonConfigurator.configureObjectMapper(objectMapper);
		directProviderUtil = Mockito.spy(new DirectProviderUtil());
		doReturn(objectMapper).when(directProviderUtil).getObjectMapper(any(CIBUser.class));
	}

	private Variable variable(String name, String type, Object value) {
		Variable variable = new Variable();
		variable.setName(name);
		variable.setType(type);
		variable.setValue(value);
		return variable;
	}

	@Test
	void serializesAParsedJsonValue() {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("a", 1);
		value.put("b", List.of("x", "y"));

		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable("payload", "json", value), user);

		// the engine accepts only a string or null for serializable types
		assertThat(dto.getValue()).isEqualTo("{\"a\":1,\"b\":[\"x\",\"y\"]}");
		assertThat(dto.getType()).isEqualTo("json");
	}

	@Test
	void serializesAParsedObjectValueAndKeepsItsValueInfo() {
		Variable variable = variable("payload", "Object", Map.of("a", 1));
		variable.setValueInfo(Map.of("objectTypeName", "java.util.HashMap", "serializationDataFormat", "application/json"));

		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable, user);

		assertThat(dto.getValue()).isEqualTo("{\"a\":1}");
		assertThat(dto.getValueInfo()).containsEntry("objectTypeName", "java.util.HashMap");
	}

	@Test
	void matchesTheJsonTypeCaseInsensitively() {
		// the frontend uses "Json" for json variables
		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable("payload", "Json", List.of(1, 2)), user);

		assertThat(dto.getValue()).isEqualTo("[1,2]");
	}

	@Test
	void leavesAnAlreadySerializedJsonValueUntouched() {
		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable("payload", "json", "{\"a\":1}"), user);

		assertThat(dto.getValue()).isEqualTo("{\"a\":1}");
	}

	@Test
	void leavesANullJsonValueNull() {
		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable("payload", "json", null), user);

		assertThat(dto.getValue()).isNull();
	}

	@Test
	void leavesPrimitiveValuesForTheEngineToConvert() {
		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable("amount", "Integer", 42), user);

		assertThat(dto.getValue()).isEqualTo(42);
		assertThat(dto.getType()).isEqualTo("Integer");
	}

	@Test
	void setsTheRtfMimeTypeForRtfFiles() {
		Variable variable = variable("letter", "File", "e1xydGYxfQ==");
		variable.setValueInfo(new HashMap<>(Map.of("filename", "letter.rtf", "mimeType", "text/plain")));

		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable, user);

		assertThat(dto.getValueInfo()).containsEntry("mimeType", "application/rtf");
		// the caller's variable stays as it was
		assertThat(variable.getValueInfo()).containsEntry("mimeType", "text/plain");
	}

	@Test
	void keepsTheMimeTypeOfOtherFiles() {
		Variable variable = variable("letter", "file", "AAAA");
		variable.setValueInfo(new HashMap<>(Map.of("filename", "letter.rtf.pdf", "mimeType", "application/pdf")));

		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable, user);

		assertThat(dto.getValueInfo()).containsEntry("mimeType", "application/pdf");
	}

	@Test
	void copesWithAFileWithoutValueInfo() {
		Variable variable = variable("letter", "file", "AAAA");
		variable.setValueInfo(null);

		VariableValueDto dto = directProviderUtil.toVariableValueDto(variable, user);

		assertThat(dto.getValueInfo()).isNullOrEmpty();
	}
}
