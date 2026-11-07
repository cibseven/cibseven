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
package org.cibseven.spin.json.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.json.SpinJsonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stored JSON stays the same across the move of Spin to Jackson 3.
 *
 * <p>The golden files in {@code org/cibseven/spin/json/golden/} were written by the released Spin 2.2.0 on
 * Jackson 2.21.5, exactly the way the engine stores an {@code application/json} object variable
 * ({@code mapJavaToInternal} + {@code writeToWriter}, object type name = {@code getCanonicalTypeName}).
 * {@code times.json} was written with Jackson 2's {@code JavaTimeModule} registered, as the Spring Boot starter
 * did; Jackson 3 supports java.time out of the box. The {@code *.j2result} files record how Spin 2.2.0 read
 * hand-written inputs ({@link Samples#READ_INPUTS}).</p>
 *
 * <p>The files were written with the beans in the package {@code poc.beans}; this test maps that package name to
 * the package of this class and changes nothing else, so the comparison stays byte by byte.</p>
 */
public class JsonGoldenFilesTest {

  protected static final String GOLDEN_PACKAGE = "poc.beans.";
  protected static final String TEST_PACKAGE = JsonGoldenFilesTest.class.getPackageName() + ".";

  protected final JacksonJsonDataFormat format = new JacksonJsonDataFormat("application/json");

  @ParameterizedTest
  @ValueSource(strings = { "customer", "itemList", "map", "shapes", "times" })
  public void shouldWriteSameJsonAsSpinOnJackson2(String name) {
    // when: the engine serializes an object value
    Object internal = format.getMapper().mapJavaToInternal(Samples.sample(name));
    String json = write(internal);

    // then
    assertThat(json).isEqualTo(golden(name + ".json"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "customer", "itemList", "map", "shapes", "times" })
  public void shouldDetectSameObjectTypeNameAsSpinOnJackson2(String name) {
    assertThat(format.getMapper().getCanonicalTypeName(Samples.sample(name))).isEqualTo(golden(name + ".type"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "customer", "itemList", "map", "shapes", "times" })
  public void shouldReadJsonWrittenBySpinOnJackson2(String name) {
    // when: the engine deserializes a stored object value
    Object internal = format.getReader().readInput(new StringReader(golden(name + ".json")));
    Object value = format.getMapper().mapInternalToJava(internal, golden(name + ".type"));

    // then
    assertThat(value).isEqualTo(Samples.sample(name));
  }

  @ParameterizedTest
  @ValueSource(strings = { "customer", "itemList", "map", "shapes", "times" })
  public void shouldRewriteObjectValueOfSpinOnJackson2ByteIdentical(String name) {
    String stored = golden(name + ".json");

    // when: deserialized to Java and serialized again (an object variable updated without changes)
    Object value = format.getMapper().mapInternalToJava(format.getReader().readInput(new StringReader(stored)),
        golden(name + ".type"));
    String rewritten = write(format.getMapper().mapJavaToInternal(value));

    // then
    assertThat(rewritten).isEqualTo(stored);
  }

  @ParameterizedTest
  @ValueSource(strings = { "customer", "itemList", "map", "shapes" })
  public void shouldRewriteJsonTreeOfSpinOnJackson2ByteIdentical(String name) {
    String stored = golden(name + ".json");

    // when: read into a tree and written again (e.g. a JSON variable updated without changes)
    String rewritten = write(format.getReader().readInput(new StringReader(stored)));

    // then
    assertThat(rewritten).isEqualTo(stored);
  }

  /**
   * {@code times.json} holds decimals in scientific notation ({@code 1.7924958E+9}, {@code 9E+1}): the JavaTimeModule
   * wrote BigDecimals that the tree stripped of trailing zeros. Read into a tree, they are doubles, so the tree is
   * written as {@code 1.7924958E9} and {@code 90.0}: Spin on Jackson 2 did exactly the same (checked with
   * cibseven-spin-dataformat-json-jackson2, i.e. the Spin 2.x code on Jackson 2.21.7). The values are unchanged.
   */
  @Test
  public void shouldRewriteJsonTreeWithScientificDecimalsLikeSpinOnJackson2() {
    String rewritten = write(format.getReader().readInput(new StringReader(golden("times.json"))));

    assertThat(rewritten)
        .isEqualTo("{\"day\":[2026,10,20],\"at\":[2026,10,20,13,30,15],\"instant\":1.7924958E9,\"duration\":90.0}");
  }

  @Test
  public void shouldFailOnUnknownPropertyLikeSpinOnJackson2() {
    assertThat(golden("unknownProperty.j2result"))
        .startsWith("EXCEPTION com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException");

    assertThatThrownBy(() -> read("unknownProperty"))
        .isInstanceOf(SpinJsonException.class)
        .hasMessageContaining("SPIN/JACKSON-JSON-01006")
        .hasRootCauseInstanceOf(UnrecognizedPropertyException.class)
        .hasStackTraceContaining("\"extra\"");
  }

  @Test
  public void shouldReadNullForPrimitiveLikeSpinOnJackson2() {
    assertThat(golden("nullPrimitive.j2result")).isEqualTo("OK Item[A-1,0]");
    assertThat(read("nullPrimitive")).isEqualTo(new Item("A-1", 0));
  }

  @Test
  public void shouldReadEnumByNameLikeSpinOnJackson2() {
    assertThat(golden("enumByName.j2result")).isEqualTo("OK Customer[n,1,null,on_hold,null,null,null,false]");
    assertThat(read("enumByName").toString()).isEqualTo("Customer[n,1,null,on_hold,null,null,null,false]");
  }

  @Test
  public void shouldReadDateAsIsoStringLikeSpinOnJackson2() {
    // the recorded result prints the date in the time zone of the recording (CEST); compare the instant instead
    assertThat(golden("dateAsIsoString.j2result")).startsWith("OK Customer[n,1,Tue Oct 20 13:30:00 CEST 2026,");
    Customer customer = (Customer) read("dateAsIsoString");
    assertThat(customer.getCreatedAt()).isEqualTo(new Date(1792495800000L));
  }

  @Test
  public void shouldIgnoreTrailingTokensLikeSpinOnJackson2() {
    assertThat(golden("trailingTokens.j2result")).isEqualTo("OK Item[A-1,2]");
    assertThat(read("trailingTokens")).isEqualTo(new Item("A-1", 2));
  }

  /**
   * Pins the settings of the default mapper that keep the JSON of Spin on Jackson 2. Jackson 3 changed the
   * defaults of all of them; {@link JsonMapper#builderWithJackson2Defaults()} restores them.
   */
  @Test
  public void shouldKeepJackson2DefaultsInDefaultMapper() {
    ObjectMapper mapper = format.getObjectMapper();

    // null for a primitive -> default value; trailing tokens ignored; unknown properties fail
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)).isFalse();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    // enums by name(), not toString()
    assertThat(mapper.isEnabled(EnumFeature.WRITE_ENUMS_USING_TO_STRING)).isFalse();
    assertThat(mapper.isEnabled(EnumFeature.READ_ENUMS_USING_TO_STRING)).isFalse();
    // properties in declaration order, not sorted
    assertThat(mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)).isFalse();
    // dates and java.time values as timestamps
    assertThat(mapper.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
    assertThat(mapper.isEnabled(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)).isTrue();
    // BigDecimal in trees without trailing zeros ("balance":1234.5)
    assertThat(mapper.isEnabled(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)).isTrue();
    assertThat(mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS)).isTrue();
    // "/" not escaped
    assertThat(((JsonFactory) mapper.tokenStreamFactory()).isEnabled(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)).isFalse();
    // nesting depth of Jackson 2
    assertThat(mapper.tokenStreamFactory().streamReadConstraints().getMaxNestingDepth()).isEqualTo(1000);
    assertThat(mapper.tokenStreamFactory().streamWriteConstraints().getMaxNestingDepth()).isEqualTo(1000);
  }

  @Test
  public void shouldWriteSlashAndNonAsciiUnescaped() {
    Item item = new Item("a/b \u00E9 \u20AC", 1);

    String json = write(format.getMapper().mapJavaToInternal(item));

    assertThat(json).isEqualTo("{\"sku\":\"a/b \u00E9 \u20AC\",\"qty\":1}");
  }

  /**
   * Characters outside the Basic Multilingual Plane (emoji), a lone surrogate, non-ASCII, control characters and a
   * quote. Spin writes through a character based generator (the engine too: an OutputStreamWriter), so Jackson 2 and
   * Jackson 3 both write emoji as raw characters, not as {@code \}{@code uXXXX} escapes. The same expected text is
   * pinned in the tests of cibseven-spin-dataformat-json-jackson2 (the Spin 2.x code on Jackson 2).
   */
  @Test
  public void shouldWriteSpecialCharactersLikeSpinOnJackson2() {
    Item item = new Item(SPECIAL_CHARACTERS, 1);

    String json = write(format.getMapper().mapJavaToInternal(item));
    String rewritten = write(format.getReader().readInput(new StringReader(json)));
    String rewrittenFromEscapes = write(format.getReader().readInput(new StringReader(SPECIAL_CHARACTERS_ESCAPED_JSON)));

    assertThat(json).isEqualTo(SPECIAL_CHARACTERS_JSON);
    assertThat(rewritten).isEqualTo(SPECIAL_CHARACTERS_JSON);
    assertThat(rewrittenFromEscapes).isEqualTo(SPECIAL_CHARACTERS_JSON);
  }

  /** Emoji (surrogate pair), euro sign, e acute, slash, control character U+0001, tab, quote, lone surrogate. */
  static final String SPECIAL_CHARACTERS =
      "smile \uD83D\uDE00 euro \u20AC \u00E9 slash / ctrl \u0001 tab \t quote \" lone \uD800 end";
  /** As written by Spin on Jackson 2 and Jackson 3: only the control characters and the quote are escaped. */
  static final String SPECIAL_CHARACTERS_JSON =
      "{\"sku\":\"smile \uD83D\uDE00 euro \u20AC \u00E9 slash / ctrl \\u0001 tab \\t quote \\\" lone \uD800 end\",\"qty\":1}";
  /** The same value with every non-ASCII character escaped in the input. */
  static final String SPECIAL_CHARACTERS_ESCAPED_JSON =
      "{\"sku\":\"smile \\uD83D\\uDE00 euro \\u20AC \\u00E9 slash \\/ ctrl \\u0001 tab \\t quote \\\" lone \\uD800 end\",\"qty\":1}";

  // helpers ////////////////////////////////////////////////////////////

  protected Object read(String inputName) {
    Object internal = format.getReader().readInput(new StringReader(Samples.READ_INPUTS.get(inputName)));
    return format.getMapper().mapInternalToJava(internal, Samples.READ_TYPES.get(inputName).getName());
  }

  protected String write(Object internal) {
    StringWriter writer = new StringWriter();
    format.getWriter().writeToWriter(writer, internal);
    return writer.toString();
  }

  /**
   * @return the content of a golden file, with the bean package of the recording mapped to the package of this test
   */
  protected static String golden(String fileName) {
    try (InputStream in = JsonGoldenFilesTest.class.getResourceAsStream(fileName)) {
      assertThat(in).as("golden file %s", fileName).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(GOLDEN_PACKAGE, TEST_PACKAGE);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

}
