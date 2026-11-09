/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.connect.ai.agent.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.Test;

/**
 * How long an answer may be and still stay a String variable, per database (CIB7-2165). The text
 * column holds 2000 characters on Oracle and 4000 elsewhere, but on DB2 its 4000 count bytes.
 */
public class AgenticTurnRunnerStorableTest {

  /** 3990 characters, twelve of them umlauts: 4002 bytes of UTF-8. */
  private static final String GERMAN = "ä".repeat(12) + "a".repeat(3978);

  @Test
  public void onDb2ATextOverFourThousandBytesIsNoString() {
    assertThat(GERMAN).hasSize(3990);

    assertThat(AgenticTurnRunner.storable(GERMAN, "db2")).isInstanceOf(ObjectValue.class);
    assertThat(AgenticTurnRunner.storable("a".repeat(4000), "db2")).isInstanceOf(String.class);
  }

  /** Postgres, MySQL and H2 count characters; MSSQL and Oracle UTF-16 units, as Java does. */
  @Test
  public void elsewhereCharactersCount() {
    assertThat(AgenticTurnRunner.storable(GERMAN, "postgres")).isEqualTo(GERMAN);
    assertThat(AgenticTurnRunner.storable("a".repeat(4000), "h2")).isInstanceOf(String.class);
    assertThat(AgenticTurnRunner.storable("a".repeat(4001), "h2")).isInstanceOf(ObjectValue.class);
    assertThat(AgenticTurnRunner.storable("a".repeat(4001), null)).isInstanceOf(ObjectValue.class);
  }

  @Test
  public void onOracleTwoThousandCharactersAreTheLimit() {
    assertThat(AgenticTurnRunner.storable("a".repeat(2000), "oracle")).isInstanceOf(String.class);
    assertThat(AgenticTurnRunner.storable("a".repeat(2001), "oracle"))
        .isInstanceOf(ObjectValue.class);
  }

  /** The connector's object is unwrapped, and a long answer goes back as a Java-serialized one. */
  @Test
  public void aLongAnswerIsAJavaSerializedObjectOfTheText() {
    Object stored = AgenticTurnRunner.storable(
        Variables.objectValue("a".repeat(5000)).create(), "postgres");

    assertThat(stored).isInstanceOf(ObjectValue.class);
    assertThat(((ObjectValue) stored).getValue()).isEqualTo("a".repeat(5000));
    assertThat(((ObjectValue) stored).getSerializationDataFormat())
        .isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
    assertThat(AgenticTurnRunner.storable(Variables.objectValue("kurz").create(), "postgres"))
        .isEqualTo("kurz");
  }
}
