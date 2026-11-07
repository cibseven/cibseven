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
package org.cibseven.bpm.client.variable.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cibseven.bpm.client.variable.json.fixture.JsonFixtures.assertMatchesFixture;
import static org.cibseven.bpm.client.variable.json.fixture.JsonFixtures.readString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.cibseven.bpm.client.exception.DataFormatException;
import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormatProvider;
import org.cibseven.bpm.client.variable.json.fixture.FixtureBean;
import org.cibseven.bpm.client.variable.json.fixture.FixtureBox;
import org.cibseven.bpm.client.variable.json.fixture.FixtureComputed;
import org.cibseven.bpm.client.variable.json.fixture.FixtureItem;
import org.cibseven.bpm.client.variable.json.fixture.FixtureItemList;
import org.cibseven.bpm.client.variable.json.fixture.FixtureShapes;
import org.cibseven.bpm.client.variable.json.fixture.FixtureStatus;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the JSON of object variables (serialization data format {@code application/json}) as the client writes
 * and reads them: the serialized value, the {@code objectTypeName} and the result of reading the stored value
 * back. The fixtures were recorded with the Jackson 2 based client; they must stay byte-identical so that
 * variables written by older clients or by the engine keep working, and vice versa.
 */
public class JsonVariableFixtureTest {

  protected DataFormat dataFormat;

  @BeforeEach
  public void setUp() {
    // the instance the client uses by default (looked up via ServiceLoader in ExternalTaskClientBuilderImpl)
    JacksonJsonDataFormatProvider provider = new JacksonJsonDataFormatProvider();
    assertThat(provider.getDataFormatName()).isEqualTo(Variables.SerializationDataFormats.JSON.getName());
    dataFormat = provider.createInstance();
  }

  // ---------------------------------------------------------------------------------------------------------------
  // write, type name, read back
  // ---------------------------------------------------------------------------------------------------------------

  static Stream<Arguments> variables() {
    return Stream.of(
        variable("bean", JsonVariableFixtureTest::fullBean, true),
        variable("bean-defaults", FixtureBean::new, true),
        variable("bean-list", () -> new ArrayList<>(List.of(fullBean(), new FixtureBean())), true),
        variable("item-list", () -> new ArrayList<>(items()), true),
        variable("item-linked-list", () -> new LinkedList<>(items()), true),
        variable("item-list-subclass", () -> {
          FixtureItemList list = new FixtureItemList();
          list.addAll(items());
          return list;
        }, true),
        variable("item-array", () -> items().toArray(new FixtureItem[0]), false),
        variable("string-list", () -> new ArrayList<>(List.of("a", "b/c", "ä")), true),
        variable("empty-list", ArrayList::new, true),
        variable("nested-list", () -> new ArrayList<>(List.of(new ArrayList<>(List.of("x", "y")),
            new ArrayList<>(List.of("z")))), true),
        variable("string-array", () -> new String[] { "one", "two" }, false),
        variable("hash-map", JsonVariableFixtureTest::hashMap, false),
        variable("strings", JsonVariableFixtureTest::strings, true),
        variable("numbers", JsonVariableFixtureTest::numbers, false),
        variable("shapes", () -> {
          FixtureShapes shapes = new FixtureShapes();
          shapes.getShapes().add(new FixtureShapes.Circle(1.5));
          shapes.getShapes().add(new FixtureShapes.Square(2));
          return shapes;
        }, true),
        variable("string", () -> "plain/text äöü €", true),
        variable("integer", () -> 42, true),
        variable("date", () -> new Date(1794054896789L), true),
        variable("big-decimal", () -> new BigDecimal("1E+3"), true),
        variable("enum", () -> FixtureStatus.ON_HOLD, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("variables")
  public void shouldWriteAndReadObjectVariable(String name, Supplier<Object> value, boolean equalAfterRead) {
    Object object = value.get();

    // when written
    String json = dataFormat.writeValue(object);
    String typeName = dataFormat.getCanonicalTypeName(object);

    // then
    assertMatchesFixture("variables/" + name + ".json", json);
    assertMatchesFixture("variables/" + name + ".type", typeName);

    // when the stored value is read back with the stored type name
    Object read = dataFormat.readValue(readString("variables/" + name + ".json"), readString("variables/" + name + ".type"));

    // then
    assertThat(read).isNotNull();
    assertThat(read.getClass()).isEqualTo(object.getClass());
    if (equalAfterRead) {
      assertThat(read).isEqualTo(object);
    }
    if (object instanceof Object[]) {
      assertThat(Arrays.deepEquals((Object[]) read, (Object[]) object)).isTrue();
    }
    assertMatchesFixture("variables/" + name + ".reread.json", dataFormat.writeValue(read));
  }

  @Test
  public void shouldReadListElementsWithTheirType() {
    // given
    String json = readString("variables/bean-list.json");
    String typeName = readString("variables/bean-list.type");

    // when
    List<?> read = dataFormat.readValue(json, typeName);

    // then
    assertThat(read).hasSize(2).allMatch(FixtureBean.class::isInstance);
  }

  // ---------------------------------------------------------------------------------------------------------------
  // type names (objectTypeName of object variables)
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * Type names of the values the client can store. Not part of it: {@code Optional} (Jackson 2 cannot serialize it
   * without the jdk8 module; Jackson 3 can and names it {@code java.util.Optional<java.lang.Object>}).
   */
  @Test
  public void shouldDetectTypeNames() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("bean", fullBean());
    values.put("item", new FixtureItem("A-1", 2));
    values.put("enum", FixtureStatus.ACTIVE);
    values.put("string", "s");
    values.put("integer", 1);
    values.put("long", 1L);
    values.put("double", 1.0d);
    values.put("boolean", Boolean.TRUE);
    values.put("bigDecimal", BigDecimal.ONE);
    values.put("bigInteger", BigInteger.ONE);
    values.put("date", new Date(0));
    values.put("localDate", LocalDate.of(2026, 11, 7));
    values.put("arrayList<item>", new ArrayList<>(items()));
    values.put("arrayList<string>", new ArrayList<>(List.of("a")));
    values.put("arrayList<integer>", new ArrayList<>(List.of(1, 2)));
    values.put("arrayList<enum>", new ArrayList<>(List.of(FixtureStatus.ON_HOLD)));
    values.put("arrayList<arrayList<string>>", new ArrayList<>(List.of(new ArrayList<>(List.of("a")))));
    values.put("arrayList<list.of>", new ArrayList<>(List.of(List.of("a"))));
    values.put("arrayList<map>", new ArrayList<>(List.of(new HashMap<>(Map.of("k", "v")))));
    values.put("arrayList<empty>", new ArrayList<>());
    values.put("arrayList<nullFirst>", new ArrayList<>(Arrays.asList(null, "x")));
    values.put("linkedList<item>", new LinkedList<>(items()));
    values.put("listSubclass", new FixtureItemList());
    values.put("list.of", List.of("a", "b", "c"));
    values.put("list.of1", List.of("a"));
    values.put("arrays.asList", Arrays.asList("a", "b"));
    values.put("singletonList", Collections.singletonList("a"));
    values.put("emptyList", Collections.emptyList());
    values.put("unmodifiableList", Collections.unmodifiableList(new ArrayList<>(List.of("a"))));
    values.put("hashMap", new HashMap<>(Map.of("k", 1)));
    values.put("linkedHashMap", new LinkedHashMap<>());
    values.put("treeMap", new TreeMap<>());
    values.put("map.of", Map.of("k", 1));
    values.put("hashSet", new HashSet<>(List.of("a")));
    values.put("box<string>", new FixtureBox<>("x"));
    values.put("string[]", new String[] { "a" });
    values.put("int[]", new int[] { 1 });
    values.put("item[]", new FixtureItem[] { new FixtureItem("A", 1) });
    values.put("shapes", new FixtureShapes());
    values.put("circle", new FixtureShapes.Circle(1));

    StringBuilder typeNames = new StringBuilder();
    values.forEach((label, value) -> {
      String typeName;
      try {
        typeName = dataFormat.getCanonicalTypeName(value);
      } catch (RuntimeException e) {
        typeName = "FAIL " + e.getClass().getSimpleName();
      }
      typeNames.append(label).append('=').append(typeName).append('\n');
    });

    assertMatchesFixture("variables/type-names.txt", typeNames.toString());
  }

  // ---------------------------------------------------------------------------------------------------------------
  // reading behaviour (lenient/strict parts of the default configuration)
  // ---------------------------------------------------------------------------------------------------------------

  @Test
  public void shouldKeepReadBehaviour() {
    String item = FixtureItem.class.getName();
    String bean = FixtureBean.class.getName();
    Function<Object, String> createdAt = b -> String.valueOf(((FixtureBean) b).getCreatedAt().getTime());

    StringBuilder results = new StringBuilder();
    read(results, "unknownProperty", "{\"sku\":\"A-1\",\"qty\":2,\"extra\":true}", item, String::valueOf);
    read(results, "nullForPrimitive", "{\"sku\":\"A-1\",\"qty\":null}", item, String::valueOf);
    read(results, "missingProperty", "{\"sku\":\"A-1\"}", item, String::valueOf);
    read(results, "trailingTokens", "{\"sku\":\"A-1\",\"qty\":2} {\"sku\":\"B-7\"}", item, String::valueOf);
    read(results, "trailingGarbage", "{\"sku\":\"A-1\",\"qty\":2}]", item, String::valueOf);
    read(results, "stringToInt", "{\"sku\":\"A-1\",\"qty\":\"5\"}", item, String::valueOf);
    read(results, "floatToInt", "{\"sku\":\"A-1\",\"qty\":2.7}", item, String::valueOf);
    read(results, "intOverflow", "{\"sku\":\"A-1\",\"qty\":3000000000}", item, String::valueOf);
    read(results, "numberToString", "{\"sku\":123,\"qty\":1}", item, String::valueOf);
    read(results, "booleanToString", "{\"sku\":true,\"qty\":1}", item, String::valueOf);
    read(results, "emptyStringToInt", "{\"sku\":\"A-1\",\"qty\":\"\"}", item, String::valueOf);
    read(results, "emptyStringAsBean", "\"\"", item, String::valueOf);
    read(results, "nullAsBean", "null", item, String::valueOf);
    read(results, "emptyObject", "{}", item, String::valueOf);
    read(results, "arrayAsBean", "[{\"sku\":\"A-1\",\"qty\":2}]", item, String::valueOf);
    read(results, "duplicateKeys", "{\"sku\":\"A-1\",\"qty\":1,\"qty\":2}", item, String::valueOf);
    read(results, "comments", "{\"sku\":\"A-1\", /* note */ \"qty\":2}", item, String::valueOf);
    read(results, "singleQuotes", "{'sku':'A-1','qty':2}", item, String::valueOf);
    read(results, "unquotedNames", "{sku:\"A-1\",qty:2}", item, String::valueOf);
    read(results, "caseInsensitiveName", "{\"SKU\":\"A-1\",\"qty\":2}", item, String::valueOf);
    read(results, "dateAsIsoString", "{\"createdAt\":\"2026-10-20T11:30:00.000Z\"}", bean, createdAt);
    read(results, "dateAsIsoStringWithOffset", "{\"createdAt\":\"2026-10-20T13:30:00.000+0200\"}", bean, createdAt);
    read(results, "dateAsIsoStringWithColonOffset", "{\"createdAt\":\"2026-10-20T13:30:00.000+02:00\"}", bean, createdAt);
    read(results, "dateAsIsoDate", "{\"createdAt\":\"2026-10-20\"}", bean, createdAt);
    read(results, "dateAsNumber", "{\"createdAt\":1792495800000}", bean, createdAt);
    read(results, "dateAsNumericString", "{\"createdAt\":\"1792495800000\"}", bean, createdAt);
    read(results, "enumByName", "{\"status\":\"ON_HOLD\"}", bean, b -> String.valueOf(((FixtureBean) b).getStatus().name()));
    read(results, "enumByToString", "{\"status\":\"on_hold\"}", bean, b -> String.valueOf(((FixtureBean) b).getStatus()));
    read(results, "enumByIndex", "{\"status\":1}", bean, b -> String.valueOf(((FixtureBean) b).getStatus().name()));
    read(results, "enumEmptyString", "{\"status\":\"\"}", bean, b -> String.valueOf(((FixtureBean) b).getStatus()));
    read(results, "intToBoolean", "{\"active\":1}", bean, b -> String.valueOf(((FixtureBean) b).isActive()));
    read(results, "stringToBoolean", "{\"active\":\"true\"}", bean, b -> String.valueOf(((FixtureBean) b).isActive()));
    read(results, "singleValueAsList", "{\"tags\":\"one\"}", bean, b -> String.valueOf(((FixtureBean) b).getTags()));
    read(results, "emptyStringAsList", "{\"tags\":\"\"}", bean, b -> String.valueOf(((FixtureBean) b).getTags()));
    read(results, "readOnlyProperty", "{\"sku\":\"A-1\",\"qty\":2,\"total\":\"A-1 x 2\"}",
        FixtureComputed.class.getName(), String::valueOf);
    read(results, "ignoredProperty", "{\"secret\":\"s\"}", bean, b -> String.valueOf(((FixtureBean) b).getSecret()));
    read(results, "renamedPropertyByJavaName", "{\"displayName\":\"d\"}", bean, b -> String.valueOf(((FixtureBean) b).getDisplayName()));
    read(results, "bigDecimalPrecision", "{\"amount\":0.1000000000000000055511151231257827}", bean,
        b -> ((FixtureBean) b).getAmount().toString());
    read(results, "bigDecimalExponent", "{\"amount\":1.5E+3}", bean, b -> ((FixtureBean) b).getAmount().toString());
    read(results, "doublePrecision", "{\"ratio\":0.1000000000000000055511151231257827}", bean,
        b -> String.valueOf(((FixtureBean) b).getRatio()));
    read(results, "nanAsString", "{\"ratio\":\"NaN\"}", bean, b -> String.valueOf(((FixtureBean) b).getRatio()));
    read(results, "nanToken", "{\"ratio\":NaN}", bean, b -> String.valueOf(((FixtureBean) b).getRatio()));
    read(results, "leadingZeros", "{\"count\":007}", bean, b -> String.valueOf(((FixtureBean) b).getCount()));
    read(results, "base64Payload", "{\"payload\":\"aGVsbG8=\"}", bean,
        b -> new String(((FixtureBean) b).getPayload(), StandardCharsets.UTF_8));
    read(results, "charFromString", "{\"initial\":\"Q\"}", bean, b -> String.valueOf(((FixtureBean) b).getInitial()));
    read(results, "charFromLongString", "{\"initial\":\"QQ\"}", bean, b -> String.valueOf(((FixtureBean) b).getInitial()));
    read(results, "mapNumberTypes", "{\"i\":1,\"l\":3000000000,\"d\":1.5,\"b\":12345678901234567890}",
        "java.util.HashMap<java.lang.Object,java.lang.Object>", JsonVariableFixtureTest::describeMapValues);
    read(results, "listOfMapsWithoutElementType", "[{\"sku\":\"A-1\",\"qty\":2}]", "java.util.ArrayList",
        l -> ((List<?>) l).get(0).getClass().getName());
    read(results, "polymorphicUnknownClass", "{\"shapes\":[{\"@class\":\"java.lang.Thread\"}]}",
        FixtureShapes.class.getName(), String::valueOf);
    read(results, "polymorphicMissingTypeId", "{\"shapes\":[{\"radius\":1.5}]}", FixtureShapes.class.getName(),
        String::valueOf);
    read(results, "unknownTypeName", "{}", "org.example.DoesNotExist", String::valueOf);
    read(results, "malformedTypeName", "{}", "java.util.ArrayList<", String::valueOf);
    read(results, "deepNesting400", nestedArrays(400), "java.util.ArrayList", x -> "nested");
    read(results, "deepNesting700", nestedArrays(700), "java.util.ArrayList", x -> "nested");
    read(results, "deepNesting1200", nestedArrays(1200), "java.util.ArrayList", x -> "nested");

    assertMatchesFixture("variables/read-behaviour.txt", results.toString());
  }

  @Test
  public void shouldKeepWriteBehaviour() {
    StringBuilder results = new StringBuilder();
    write(results, "emptyBean", new Object());
    write(results, "selfReference", selfReferencingList());
    write(results, "nullValue", null);
    write(results, "readOnlyProperty", new FixtureComputed("A-1", 2));
    write(results, "deepNesting400", nestedLists(400));
    write(results, "deepNesting700", nestedLists(700));
    write(results, "deepNesting1200", nestedLists(1200));

    assertMatchesFixture("variables/write-behaviour.txt", results.toString());
  }

  // ---------------------------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------------------------

  protected void read(StringBuilder results, String label, String json, String typeName, Function<Object, String> render) {
    String outcome;
    try {
      Object value = dataFormat.readValue(json, typeName);
      outcome = "OK " + (value == null ? "null" : render.apply(value));
    } catch (DataFormatException e) {
      outcome = "FAIL " + e.getClass().getSimpleName();
    } catch (RuntimeException e) {
      outcome = "FAIL (unwrapped) " + e.getClass().getSimpleName();
    }
    results.append(label).append(": ").append(outcome).append('\n');
  }

  protected void write(StringBuilder results, String label, Object value) {
    String outcome;
    try {
      String json = dataFormat.writeValue(value);
      outcome = "OK " + (json.length() > 200 ? json.length() + " chars" : json);
    } catch (DataFormatException e) {
      outcome = "FAIL " + e.getClass().getSimpleName();
    } catch (RuntimeException | StackOverflowError e) {
      outcome = "FAIL (unwrapped) " + e.getClass().getSimpleName();
    }
    results.append(label).append(": ").append(outcome).append('\n');
  }

  protected static Arguments variable(String name, Supplier<Object> value, boolean equalAfterRead) {
    return Arguments.of(name, value, equalAfterRead);
  }

  public static FixtureBean fullBean() {
    FixtureBean bean = new FixtureBean();
    bean.setName("Order 4711 \"express\"");
    bean.setCount(42);
    bean.setBigCount(9_007_199_254_740_993L);
    bean.setActive(true);
    bean.setFlag(Boolean.FALSE);
    bean.setRatio(0.1);
    bean.setFactor(1.5f);
    bean.setAmount(new BigDecimal("1234.50"));
    bean.setHuge(new BigInteger("123456789012345678901234567890"));
    bean.setCreatedAt(Date.from(Instant.parse("2026-10-20T11:30:00.123Z")));
    bean.setStatus(FixtureStatus.ON_HOLD);
    bean.setTags(new ArrayList<>(List.of("urgent", "a/b")));
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("zeta", "last");
    attributes.put("alpha", 1);
    attributes.put("nested", new LinkedHashMap<>(Map.of("k", "v")));
    attributes.put("list", new ArrayList<>(List.of("x", "y")));
    attributes.put("none", null);
    attributes.put("yes", true);
    bean.setAttributes(attributes);
    bean.setMainItem(new FixtureItem("M-1", 1));
    bean.setItems(new ArrayList<>(items()));
    bean.setPayload("hello".getBytes(StandardCharsets.UTF_8));
    bean.setInitial('Z');
    bean.setDisplayName("Express order");
    bean.setSecret("not written");
    return bean;
  }

  protected static List<FixtureItem> items() {
    return List.of(new FixtureItem("A-1", 2), new FixtureItem("B-7", 1));
  }

  protected static Map<String, Object> hashMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("count", 3);
    map.put("label", "x");
    map.put("nested", new ArrayList<>(List.of("a", "b")));
    map.put("inner", new HashMap<>(Map.of("deep", true)));
    map.put("missing", null);
    map.put("price", new BigDecimal("19.99"));
    map.put("when", new Date(1794054896789L));
    map.put("item", new FixtureItem("C-3", 3));
    return map;
  }

  protected static Map<String, Object> strings() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("slash", "a/b");
    map.put("quote", "say \"hi\"");
    map.put("backslash", "C:\\temp");
    map.put("newline", "line1\nline2");
    map.put("tab", "a\tb");
    map.put("control", "\u0001\u001f");
    map.put("delete", "\u007f");
    map.put("unicode", "äöü ß € 漢字");
    map.put("emoji", "\uD83D\uDE00");
    map.put("html", "<b>&amp;</b> </script>");
    map.put("separators", "\u2028\u2029");
    map.put("empty", "");
    return map;
  }

  protected static Map<String, Object> numbers() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("int", 42);
    map.put("negInt", -7);
    map.put("long", 9_007_199_254_740_993L);
    map.put("short", (short) 3);
    map.put("byte", (byte) 4);
    map.put("double", 0.1);
    map.put("doubleExp", 1.0E10);
    map.put("doubleLarge", 1.0E21);
    map.put("small", 1.0E-5);
    map.put("pi", Math.PI);
    map.put("negZero", -0.0);
    map.put("float", 1.1f);
    map.put("floatSmall", 1.0E-7f);
    map.put("maxDouble", Double.MAX_VALUE);
    map.put("minDouble", Double.MIN_VALUE);
    map.put("nan", Double.NaN);
    map.put("posInf", Double.POSITIVE_INFINITY);
    map.put("bigDecimalExp", new BigDecimal("1E+3"));
    map.put("bigDecimalSmall", new BigDecimal("0.000001"));
    map.put("bigDecimalScale", new BigDecimal("1234.50"));
    map.put("bigDecimalLong", new BigDecimal("1234567890.12345678901234567890"));
    map.put("bigInteger", new BigInteger("123456789012345678901234567890"));
    return map;
  }

  protected static String describeMapValues(Object map) {
    StringBuilder sb = new StringBuilder();
    new TreeMap<>((Map<?, ?>) map).forEach((k, v) -> sb.append(k).append('=').append(v.getClass().getSimpleName())
        .append('(').append(v).append(") "));
    return sb.toString().trim();
  }

  protected static String nestedArrays(int depth) {
    return "[".repeat(depth) + "]".repeat(depth);
  }

  protected static List<Object> nestedLists(int depth) {
    List<Object> outer = new ArrayList<>();
    List<Object> current = outer;
    for (int i = 1; i < depth; i++) {
      List<Object> inner = new ArrayList<>();
      current.add(inner);
      current = inner;
    }
    return outer;
  }

  protected static List<Object> selfReferencingList() {
    List<Object> list = new ArrayList<>();
    list.add(list);
    return list;
  }

}
