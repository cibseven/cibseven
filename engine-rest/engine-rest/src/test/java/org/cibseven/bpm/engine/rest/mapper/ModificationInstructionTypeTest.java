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
package org.cibseven.bpm.engine.rest.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.cibseven.bpm.engine.rest.dto.ModificationDto;
import org.cibseven.bpm.engine.rest.dto.runtime.RestartProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.StartProcessInstanceDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.CancellationInstructionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.ProcessInstanceModificationDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.ProcessInstanceModificationInstructionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.StartAfterInstructionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.StartBeforeInstructionDto;
import org.cibseven.bpm.engine.rest.dto.runtime.modification.StartTransitionInstructionDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code type} is both the type id of a modification instruction and a property of it. Jackson 3.2
 * rejects that with {@code As.PROPERTY} ("Conflict between type id property 'type' and bean
 * property with same name"), for reading and for writing, so the type id is the existing
 * property (CIB7-2841).
 */
public class ModificationInstructionTypeTest {

  static ObjectMapper mapper;

  @BeforeAll
  public static void setUpMapper() {
    mapper = new JacksonConfigurator().getContext(Object.class);
  }

  static Stream<Arguments> instructionTypes() {
    return Stream.of(
        Arguments.of("cancel", CancellationInstructionDto.class),
        Arguments.of("startBeforeActivity", StartBeforeInstructionDto.class),
        Arguments.of("startAfterActivity", StartAfterInstructionDto.class),
        Arguments.of("startTransition", StartTransitionInstructionDto.class));
  }

  @Test
  public void shouldUseTheExistingTypeProperty() {
    JsonTypeInfo typeInfo = ProcessInstanceModificationInstructionDto.class.getAnnotation(JsonTypeInfo.class);

    assertThat(typeInfo.include()).isEqualTo(JsonTypeInfo.As.EXISTING_PROPERTY);
    assertThat(typeInfo.property()).isEqualTo("type");
    assertThat(typeInfo.visible()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("instructionTypes")
  public void shouldReadEachInstructionTypeAsItsSubtype(String type, Class<?> subtype) throws Exception {
    ProcessInstanceModificationInstructionDto instruction = mapper.readValue(
        "{\"activityId\":\"a\",\"type\":\"" + type + "\"}", ProcessInstanceModificationInstructionDto.class);

    assertThat(instruction).isExactlyInstanceOf(subtype);
    assertThat(instruction.getType()).isEqualTo(type);
    assertThat(instruction.getActivityId()).isEqualTo("a");
  }

  @ParameterizedTest
  @MethodSource("instructionTypes")
  public void shouldWriteTheTypeOfANewInstructionOnce(String type, Class<?> subtype) throws Exception {
    Object instruction = subtype.getDeclaredConstructor().newInstance();

    String json = mapper.writeValueAsString(instruction);

    assertThat(json).startsWith("{\"type\":\"" + type + "\"");
    assertThat(json.split("\"type\"", -1)).hasSize(2);
  }

  @ParameterizedTest
  @MethodSource("instructionTypes")
  public void shouldReadWhatItWrites(String type, Class<?> subtype) throws Exception {
    ProcessInstanceModificationInstructionDto instruction =
        (ProcessInstanceModificationInstructionDto) subtype.getDeclaredConstructor().newInstance();
    instruction.setActivityId("a");

    ProcessInstanceModificationInstructionDto read = mapper.readValue(
        mapper.writeValueAsString(instruction), ProcessInstanceModificationInstructionDto.class);

    assertThat(read).isExactlyInstanceOf(subtype);
    assertThat(read.getType()).isEqualTo(type);
    assertThat(read.getActivityId()).isEqualTo("a");
  }

  @Test
  public void shouldReadInstructionsOfEveryRequestThatHoldsThem() throws Exception {
    String instructions = "[{\"type\":\"startBeforeActivity\",\"activityId\":\"a\"},{\"type\":\"cancel\",\"activityId\":\"b\"}]";

    ProcessInstanceModificationDto modification = mapper.readValue(
        "{\"instructions\":" + instructions + "}", ProcessInstanceModificationDto.class);
    ModificationDto batchModification = mapper.readValue(
        "{\"processDefinitionId\":\"pd\",\"instructions\":" + instructions + "}", ModificationDto.class);
    RestartProcessInstanceDto restart = mapper.readValue(
        "{\"processInstanceIds\":[\"p1\"],\"instructions\":" + instructions + "}", RestartProcessInstanceDto.class);
    StartProcessInstanceDto start = mapper.readValue(
        "{\"startInstructions\":" + instructions + "}", StartProcessInstanceDto.class);

    for (List<ProcessInstanceModificationInstructionDto> read : List.of(modification.getInstructions(),
        batchModification.getInstructions(), restart.getInstructions(), start.getStartInstructions())) {
      assertThat(read).extracting(ProcessInstanceModificationInstructionDto::getType)
          .containsExactly("startBeforeActivity", "cancel");
      assertThat(read.get(0)).isExactlyInstanceOf(StartBeforeInstructionDto.class);
      assertThat(read.get(1)).isExactlyInstanceOf(CancellationInstructionDto.class);
    }
  }

  @Test
  public void shouldRejectAnUnknownType() {
    assertThatThrownBy(() -> mapper.readValue(
        "{\"type\":\"unknownType\",\"activityId\":\"a\"}", ProcessInstanceModificationInstructionDto.class))
      .isInstanceOf(DatabindException.class);
  }

  @Test
  public void shouldRejectAMissingType() {
    assertThatThrownBy(() -> mapper.readValue(
        "{\"activityId\":\"a\"}", ProcessInstanceModificationInstructionDto.class))
      .isInstanceOf(DatabindException.class);
  }
}
