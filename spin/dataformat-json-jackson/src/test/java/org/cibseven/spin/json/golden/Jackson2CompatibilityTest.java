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
import static org.assertj.core.api.Assertions.entry;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Month;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Cases where Jackson 3 differs from Jackson 2 out of the box and the Spin mapper keeps the Jackson 2 output.
 * The same test runs unchanged in cibseven-spin-dataformat-json-jackson (Jackson 3) and in
 * cibseven-spin-dataformat-json-jackson2 (Jackson 2), so both variants write the same stored JSON.
 */
public class Jackson2CompatibilityTest {

  protected final JacksonJsonDataFormat format = new JacksonJsonDataFormat("application/json");

  public static class LocaleHolder {
    public Locale locale = Locale.US;
    public Locale language = Locale.GERMAN;
    public Locale root = Locale.ROOT;
  }

  public static class PolymorphicLocale {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Object value = Locale.US;
  }

  public static class NonEmptyLocales {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Locale root = Locale.ROOT;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Locale locale = Locale.US;
  }

  public static class FormattedDates {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Date date = new Date(0);
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Calendar calendar;

    public FormattedDates() {
      calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      calendar.setTimeInMillis(0);
    }
  }

  /** Accessors whose property names Jackson 3 builds differently or not at all. */
  public static class Accessors {
    private String url = "u";
    private String urlValue = "uv";
    private int x = 1;
    private int xCoordinate = 2;
    private boolean ok = true;
    private String iban = "i";
    private String abc = "abc";
    private String links = "l";
    private String value = "v";
    private String first = "f";
    private String a1 = "a1";

    public String getURL() { return url; }
    public void setURL(String url) { this.url = url; }
    public String getURLValue() { return urlValue; }
    public void setURLValue(String urlValue) { this.urlValue = urlValue; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getXCoordinate() { return xCoordinate; }
    public void setXCoordinate(int xCoordinate) { this.xCoordinate = xCoordinate; }
    public boolean isOK() { return ok; }
    public void setOK(boolean ok) { this.ok = ok; }
    public String getIBAN() { return iban; }
    public void setIBAN(String iban) { this.iban = iban; }
    public String getaBc() { return abc; }
    public void setaBc(String abc) { this.abc = abc; }
    public String get_links() { return links; }
    public void set_links(String links) { this.links = links; }
    public String getvalue() { return value; }
    public void setvalue(String value) { this.value = value; }
    public String get1st() { return first; }
    public void set1st(String first) { this.first = first; }
    public String getA1() { return a1; }
    public void setA1(String a1) { this.a1 = a1; }
  }

  /** Every property has a field of the same name (Jackson 2 naming): the field order is the property order. */
  public static class FieldOrder {
    private String url = "u";
    private boolean ok = true;
    private String iban = "i";
    private int x = 1;

    public String getURL() { return url; }
    public boolean isOK() { return ok; }
    public String getIBAN() { return iban; }
    public int getX() { return x; }
  }

  /** A record with additional getters; Jackson 3 names getURL() like the component URL. */
  public record RecordAccessors(String URL, String getValue) {
    public String getURL() { return "extra"; }
    public boolean isOK() { return true; }
  }

  /** What Jackson 2 writes for {@code new RecordAccessors("u", "v")}. */
  protected static final String RECORD_ACCESSORS_JSON = "{\"URL\":\"u\",\"getValue\":\"v\",\"url\":\"extra\",\"ok\":true}";

  public static class MonthValue {
    public Month month = Month.MARCH;
    public Map<Month, Integer> keys = new LinkedHashMap<>(Map.of(Month.JANUARY, 1));
  }

  public static class VoidGetter {
    public int getValue() { return 1; }
    public Void getNothing() { return null; }
  }

  public static class LocaleValue {
    public Locale locale;
    public Map<Locale, Integer> keys = new LinkedHashMap<>();
  }

  public enum Priority { LOW, MEDIUM, HIGH }

  /** Map and Set with an enum key or element, declared as the interfaces. */
  public static class EnumContainers {
    public Map<Priority, Integer> perPriority;
    public Set<Priority> enabled;
    public Map<String, Map<Priority, Integer>> nested;
  }

  /** Declarations Jackson 2 and Jackson 3 already read into the same classes. */
  public static class OtherContainers {
    public SortedMap<Priority, Integer> sortedMap;
    public TreeMap<Priority, Integer> treeMap;
    public SortedSet<Priority> sortedSet;
    public EnumMap<Priority, Integer> enumMap;
    public EnumSet<Priority> enumSet;
    public Map<String, Integer> stringKeys;
    public Set<String> strings;
    public Collection<Priority> collection;
    public List<Priority> list;
  }

  /** Map properties whose values carry a type id. */
  public static class PolymorphicMapValues {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = HashMap.class, name = "hash"))
    public Map<String, Map<Priority, Integer>> enumKeys;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = HashMap.class, name = "hash"))
    public Map<String, Map<String, Integer>> stringKeys;
  }

  /** A set of enums inside a map, and an enum-keyed map that is merged into instead of replaced. */
  public static class NestedAndMergedEnumContainers {
    public Map<String, Set<Priority>> setsByName;

    @JsonMerge
    public Map<Priority, Integer> merged = new LinkedHashMap<>();

    public NestedAndMergedEnumContainers() {
      merged.put(Priority.MEDIUM, 2);
    }
  }

  /** What Jackson 2 writes for an {@link EnumContainers} with a LinkedHashMap HIGH=3, LOW=1. */
  protected static final String ENUM_CONTAINERS_JSON =
      "{\"perPriority\":{\"HIGH\":3,\"LOW\":1},\"enabled\":null,\"nested\":{\"a\":{\"HIGH\":3,\"LOW\":1}}}";

  /** JSON with the same entries in every property of {@link OtherContainers}. */
  protected static final String OTHER_CONTAINERS_JSON = "{\"sortedMap\":{\"HIGH\":3,\"LOW\":1},"
      + "\"treeMap\":{\"HIGH\":3,\"LOW\":1},\"sortedSet\":[\"HIGH\",\"LOW\"],\"enumMap\":{\"HIGH\":3,\"LOW\":1},"
      + "\"enumSet\":[\"HIGH\",\"LOW\"],\"stringKeys\":{\"b\":2,\"a\":1},\"strings\":[\"b\",\"a\"],"
      + "\"collection\":[\"HIGH\",\"LOW\"],\"list\":[\"HIGH\",\"LOW\"]}";

  /** An unwrapped class with a computed property: Jackson writes "label", and there is nothing to set it on. */
  public static class Spot {
    public String street = "Main 1";
    public String city = "Bonn";

    public String getLabel() {
      return street + ", " + city;
    }
  }

  public static class Located {
    public String name = "Shop";
    @JsonUnwrapped
    public Spot spot = new Spot();
  }

  public static class Floor {
    public String level = "2";
    @JsonUnwrapped
    public Spot spot = new Spot();
  }

  /** Unwraps a class that unwraps another one. */
  public static class Building {
    public String id = "B1";
    @JsonUnwrapped
    public Floor floor = new Floor();
  }

  /** Two unwrapped properties of the same class, told apart by prefixes. */
  public static class Contacts {
    @JsonUnwrapped(prefix = "home_")
    public Spot home = new Spot();
    @JsonUnwrapped(prefix = "work_")
    public Spot work = new Spot();
  }

  public static class Address {
    public String street;
    public String city;
  }

  public static class Person {
    public String name;
    @JsonUnwrapped
    public Address address;
  }

  public static class Plain {
    public String name;
  }

  /** Holds an unwrapped class and, as a normal property, a class without @JsonUnwrapped. */
  public static class PersonWithPlain {
    public String name;
    @JsonUnwrapped
    public Address address;
    public Plain plain;
  }

  /** {@code @JsonUnwrapped} on types Jackson cannot unwrap: the annotation has no effect. */
  public static class Tagged {
    public String name;
    @JsonUnwrapped
    public String tag;
    @JsonUnwrapped
    public Map<String, Object> attributes;
    @JsonUnwrapped
    public List<String> items;
  }

  /** {@code @JsonUnwrapped} on an interface: Jackson has no deserializer that could unwrap it. */
  public interface Badge {
  }

  public static class Badged {
    public String name;
    @JsonUnwrapped
    public Badge badge;
  }

  /** Ignores the property of {@link Tagged} whose {@code @JsonUnwrapped} has no effect. */
  public static class TaggedHolder {
    @JsonIgnoreProperties("tag")
    public Tagged tagged;
  }

  /** Has a non-static inner class that unwraps a class and refers back to its outer class. */
  public static class Office {
    public String name;
    public Desk desk;

    public class Desk {
      public String id;
      @JsonUnwrapped
      public Spot spot;
      public Office office;
    }
  }

  /** Holds the inner class of {@link Office}, so that Jackson can meet it before {@link Office}. */
  public static class DeskHolder {
    public Office.Desk desk;
  }

  /** An unwrapping class that holds itself, in a list and in a field. */
  public static class Site {
    public String name;
    @JsonUnwrapped
    public Spot spot = new Spot();
    public List<Site> parts;
    public Site next;
  }

  /** What Jackson 2 writes for {@link Accessors}; the order of the properties without a field is not fixed. */
  protected static final String ACCESSORS_JSON = "{\"url\":\"u\",\"x\":1,\"ok\":true,\"iban\":\"i\",\"value\":\"v\",\"a1\":\"a1\","
      + "\"urlvalue\":\"uv\",\"xcoordinate\":2,\"aBc\":\"abc\",\"_links\":\"l\",\"1st\":\"f\"}";

  /** Locales whose {@link Locale#toString()} has a script or extensions ("zh_TW_#Hant"). */
  protected static final List<String> LOCALES_WITH_SCRIPT_OR_EXTENSIONS = List.of("zh-Hant-TW", "sr-Latn-RS",
      "th-TH-u-nu-thai", "en-Latn-US-u-ca-japanese", "ja-JP-u-ca-japanese-x-lvariant-JP");

  @Test
  public void shouldWriteLocaleWithUnderscore() {
    // Jackson 3 alone writes the language tag "en-US"
    assertThat(write(new LocaleHolder())).isEqualTo("{\"locale\":\"en_US\",\"language\":\"de\",\"root\":\"\"}");
  }

  @Test
  public void shouldReadLocaleInBothForms() {
    assertThat(read("{\"locale\":\"en_US\"}", LocaleHolder.class).locale).isEqualTo(Locale.US);
    assertThat(read("{\"locale\":\"en-US\"}", LocaleHolder.class).locale).isEqualTo(Locale.US);
  }

  @Test
  public void shouldWriteLocaleWithTypeInformation() {
    assertThat(write(new PolymorphicLocale())).isEqualTo("{\"value\":[\"java.util.Locale\",\"en_US\"]}");
  }

  @Test
  public void shouldSkipRootLocaleAsEmpty() {
    // Locale.ROOT is written as "" and counts as empty, so NON_EMPTY leaves it out
    assertThat(write(new NonEmptyLocales())).isEqualTo("{\"locale\":\"en_US\"}");
  }

  @Test
  public void shouldNameAccessorsLikeJackson2() {
    // Jackson 3 alone writes {"x":1,"a1":"a1","URL":"u","URLValue":"uv","XCoordinate":2,"OK":true,"IBAN":"i"}
    assertThat(read(write(new Accessors()), Map.class)).isEqualTo(read(ACCESSORS_JSON, Map.class));
    // Jackson 3 alone writes {"x":1,"URL":"u","OK":true,"IBAN":"i"}
    assertThat(write(new FieldOrder())).isEqualTo("{\"url\":\"u\",\"ok\":true,\"iban\":\"i\",\"x\":1}");
  }

  @Test
  public void shouldReadAccessorsLikeJackson2() {
    Accessors accessors = read(ACCESSORS_JSON.replace("\"u\"", "\"U\"").replace("\"l\"", "\"L\"").replace("\"f\"", "\"F\"")
        .replace("\"uv\"", "\"UV\"").replace("\"abc\"", "\"ABC\"").replace("2", "3"), Accessors.class);

    assertThat(accessors.getURL()).isEqualTo("U");
    assertThat(accessors.getURLValue()).isEqualTo("UV");
    assertThat(accessors.getXCoordinate()).isEqualTo(3);
    assertThat(accessors.getaBc()).isEqualTo("ABC");
    assertThat(accessors.get_links()).isEqualTo("L");
    assertThat(accessors.get1st()).isEqualTo("F");
  }

  @Test
  public void shouldNameAdditionalRecordGettersLikeJackson2() {
    // Jackson 3 alone writes {"URL":"extra","getValue":"v","OK":true}: the component's value is lost
    assertThat(read(write(new RecordAccessors("u", "v")), Map.class)).isEqualTo(read(RECORD_ACCESSORS_JSON, Map.class));
    assertThat(read("{\"URL\":\"u\",\"getValue\":\"v\"}", RecordAccessors.class)).isEqualTo(new RecordAccessors("u", "v"));
  }

  @Test
  public void shouldWriteMonthAsEnumLikeJackson2() {
    // Jackson 3 alone writes the month number: {"month":2,"keys":{"JANUARY":1}}
    String json = write(new MonthValue());

    assertThat(json).isEqualTo("{\"month\":\"MARCH\",\"keys\":{\"JANUARY\":1}}");
    MonthValue readBack = read(json, MonthValue.class);
    assertThat(readBack.month).isEqualTo(Month.MARCH);
    assertThat(readBack.keys).containsOnlyKeys(Month.JANUARY);
  }

  @Test
  public void shouldWriteSqlDateAsTimestampLikeJackson2() {
    assertThat(write(Map.of("date", new java.sql.Date(0)))).isEqualTo("{\"date\":0}");
  }

  @Test
  public void shouldNotWriteVoidGetters() {
    // Jackson 3 alone writes {"value":1,"nothing":null}
    assertThat(write(new VoidGetter())).isEqualTo("{\"value\":1}");
  }

  @Test
  public void shouldReadLocalesWithScriptOrExtensions() {
    for (String tag : LOCALES_WITH_SCRIPT_OR_EXTENSIONS) {
      // given
      LocaleValue value = new LocaleValue();
      value.locale = Locale.forLanguageTag(tag);
      value.keys.put(value.locale, 1);

      // when: Jackson 3 alone reads "zh_TW_#Hant" as zh-TW
      LocaleValue readBack = read(write(value), LocaleValue.class);

      // then
      assertThat(readBack.locale).as(tag).isEqualTo(value.locale);
      assertThat(readBack.keys).as(tag).containsOnlyKeys(value.locale);
    }
  }

  @Test
  public void shouldWriteStringShapedDatesWithNumericZeroOffset() {
    // Jackson 3 alone writes a zero offset as "Z"
    assertThat(write(new FormattedDates()))
        .isEqualTo("{\"date\":\"1970-01-01T00:00:00.000+00:00\",\"calendar\":\"1970-01-01T00:00:00.000+00:00\"}");
  }

  @Test
  public void shouldRejectStringsLongerThan20MillionCharacters() {
    // the string limit of Jackson 2; Jackson 3 alone accepts up to 100 million characters
    String json = "\"" + "a".repeat(20_000_001) + "\"";

    assertThatThrownBy(() -> format.getReader().readInput(new StringReader(json)))
        .hasStackTraceContaining("20000000");
  }

  @Test
  public void shouldReadEnumKeyedMapInInsertionOrderLikeJackson2() {
    // when: Jackson 3 alone reads Map<ENUM, V> as EnumMap (databind#1853)
    EnumContainers value = read(ENUM_CONTAINERS_JSON, EnumContainers.class);

    // then
    assertThat(value.perPriority).isExactlyInstanceOf(LinkedHashMap.class)
        .containsExactly(entry(Priority.HIGH, 3), entry(Priority.LOW, 1));
    assertThat(value.nested.get("a")).isExactlyInstanceOf(LinkedHashMap.class)
        .containsExactly(entry(Priority.HIGH, 3), entry(Priority.LOW, 1));
    // a variable read and stored again keeps its JSON; Jackson 3 alone writes {"LOW":1,"HIGH":3}
    assertThat(write(value)).isEqualTo(ENUM_CONTAINERS_JSON);
  }

  @Test
  public void shouldReadSetOfEnumsAsHashSetLikeJackson2() {
    // when: Jackson 3 alone reads Set<ENUM> as EnumSet (databind#1853), which fails on null
    EnumContainers value = read("{\"enabled\":[\"HIGH\",null,\"LOW\"]}", EnumContainers.class);

    // then
    assertThat(value.enabled).isExactlyInstanceOf(HashSet.class)
        .containsExactlyInAnyOrder(Priority.HIGH, null, Priority.LOW);
  }

  @Test
  public void shouldReadEnumKeyedMapAndSetOfEnumsAsRootTypeLikeJackson2() {
    // type names as in mapTo("java.util.Map<...>"); Jackson 3 alone reads EnumMap and EnumSet
    Object map = read("{\"HIGH\":3,\"LOW\":1}", "java.util.Map<" + Priority.class.getName() + ",java.lang.Integer>");
    Object set = read("[\"HIGH\",\"LOW\"]", "java.util.Set<" + Priority.class.getName() + ">");

    assertThat(map).isExactlyInstanceOf(LinkedHashMap.class);
    assertThat(write(map)).isEqualTo("{\"HIGH\":3,\"LOW\":1}");
    assertThat(set).isExactlyInstanceOf(HashSet.class);
  }

  @Test
  public void shouldReadOtherContainerTypesLikeJackson2() {
    // the same in Jackson 2 and Jackson 3: only Map and Set of enums are read differently
    OtherContainers value = read(OTHER_CONTAINERS_JSON, OtherContainers.class);

    assertThat(value.sortedMap).isExactlyInstanceOf(TreeMap.class).containsExactly(entry(Priority.LOW, 1), entry(Priority.HIGH, 3));
    assertThat(value.treeMap).isExactlyInstanceOf(TreeMap.class);
    assertThat(value.sortedSet).isExactlyInstanceOf(TreeSet.class).containsExactly(Priority.LOW, Priority.HIGH);
    assertThat(value.enumMap).isExactlyInstanceOf(EnumMap.class).containsExactly(entry(Priority.LOW, 1), entry(Priority.HIGH, 3));
    assertThat(value.enumSet).isInstanceOf(EnumSet.class).containsExactly(Priority.LOW, Priority.HIGH);
    assertThat(value.stringKeys).isExactlyInstanceOf(LinkedHashMap.class).containsExactly(entry("b", 2), entry("a", 1));
    assertThat(value.strings).isExactlyInstanceOf(HashSet.class);
    assertThat(value.collection).isExactlyInstanceOf(ArrayList.class).containsExactly(Priority.HIGH, Priority.LOW);
    assertThat(value.list).isExactlyInstanceOf(ArrayList.class).containsExactly(Priority.HIGH, Priority.LOW);
  }

  @Test
  public void shouldCheckTypeIdsOfMapValuesLikeJackson2() {
    PolymorphicMapValues value = read("{\"enumKeys\":{\"a\":{\"kind\":\"hash\",\"HIGH\":3}},"
        + "\"stringKeys\":{\"a\":{\"kind\":\"hash\",\"b\":2}}}", PolymorphicMapValues.class);
    assertThat(value.enumKeys.get("a")).isExactlyInstanceOf(HashMap.class).containsExactly(entry(Priority.HIGH, 3));
    assertThat(value.stringKeys.get("a")).isExactlyInstanceOf(HashMap.class).containsExactly(entry("b", 2));

    // unknown and missing type ids fail as in Jackson 2; with LinkedHashMap as default implementation they would not
    assertThatThrownBy(() -> read("{\"enumKeys\":{\"a\":{\"kind\":\"bogus\",\"HIGH\":3}}}", PolymorphicMapValues.class))
        .hasStackTraceContaining("bogus");
    assertThatThrownBy(() -> read("{\"enumKeys\":{\"a\":{\"HIGH\":3}}}", PolymorphicMapValues.class))
        .hasStackTraceContaining("missing type id");
    assertThatThrownBy(() -> read("{\"stringKeys\":{\"a\":{\"kind\":\"bogus\",\"b\":2}}}", PolymorphicMapValues.class))
        .hasStackTraceContaining("bogus");
    assertThatThrownBy(() -> read("{\"stringKeys\":{\"a\":{\"b\":2}}}", PolymorphicMapValues.class))
        .hasStackTraceContaining("missing type id");
  }

  @Test
  public void shouldReadSetOfEnumsInsideAMapLikeJackson2() {
    // Jackson 3 alone reads the inner Set<ENUM> as EnumSet too, which fails on null
    NestedAndMergedEnumContainers value = read("{\"setsByName\":{\"a\":[\"HIGH\",null]}}",
        NestedAndMergedEnumContainers.class);

    assertThat(value.setsByName.get("a")).isExactlyInstanceOf(HashSet.class)
        .containsExactlyInAnyOrder(Priority.HIGH, null);
  }

  @Test
  public void shouldMergeIntoEnumKeyedMapLikeJackson2() {
    // @JsonMerge keeps the initial LinkedHashMap; Jackson 3 alone fails to merge it into an EnumMap
    NestedAndMergedEnumContainers value = read("{\"merged\":{\"HIGH\":3}}", NestedAndMergedEnumContainers.class);

    assertThat(value.merged).isExactlyInstanceOf(LinkedHashMap.class)
        .containsExactly(entry(Priority.MEDIUM, 2), entry(Priority.HIGH, 3));
  }

  @Test
  public void shouldReadBackUnwrappedClassWithComputedProperty() {
    Located value = new Located();
    value.name = "Kiosk";
    value.spot.street = "Rhine 7";
    value.spot.city = "Cologne";
    String json = write(value);

    assertThat(json).isEqualTo("{\"name\":\"Kiosk\",\"street\":\"Rhine 7\",\"city\":\"Cologne\",\"label\":\"Rhine 7, Cologne\"}");
    // Jackson 3.1 alone (databind#650): Unrecognized property "label" (class ...Located)
    Located located = read(json, Located.class);
    assertThat(located.name).isEqualTo("Kiosk");
    assertThat(located.spot.street).isEqualTo("Rhine 7");
    assertThat(located.spot.city).isEqualTo("Cologne");
    assertThat(write(located)).isEqualTo(json);
  }

  @Test
  public void shouldReadBackNestedAndPrefixedUnwrappedClasses() {
    Building value = new Building();
    value.id = "B9";
    value.floor.level = "5";
    value.floor.spot.street = "Rhine 7";
    value.floor.spot.city = "Cologne";
    String json = write(value);
    // Jackson 3.1 alone: Unrecognized property "label" (class ...Building)
    Building building = read(json, Building.class);
    assertThat(building.id).isEqualTo("B9");
    assertThat(building.floor.level).isEqualTo("5");
    assertThat(building.floor.spot.street).isEqualTo("Rhine 7");
    assertThat(building.floor.spot.city).isEqualTo("Cologne");
    assertThat(write(building)).isEqualTo(json);

    Contacts contacts = new Contacts();
    contacts.home.street = "Home 1";
    contacts.home.city = "Bonn";
    contacts.work.street = "Work 2";
    contacts.work.city = "Essen";
    json = write(contacts);
    assertThat(json).contains("\"home_label\":\"Home 1, Bonn\"", "\"work_label\":\"Work 2, Essen\"");
    // Jackson 3.1 alone: Unrecognized property "home_label" (class ...Contacts)
    Contacts readBack = read(json, Contacts.class);
    assertThat(readBack.home.street).isEqualTo("Home 1");
    assertThat(readBack.home.city).isEqualTo("Bonn");
    assertThat(readBack.work.street).isEqualTo("Work 2");
    assertThat(readBack.work.city).isEqualTo("Essen");
    assertThat(write(readBack)).isEqualTo(json);
  }

  @Test
  public void shouldIgnoreRemovedPropertyOfUnwrappedClass() {
    // stored by an earlier version of the application whose Address still had "fax"
    String stored = "{\"name\":\"Old\",\"street\":\"Main 1\",\"city\":\"Bonn\",\"fax\":\"0228-1\"}";

    // Jackson 3.1 alone: Unrecognized property "fax" (class ...Person)
    Person person = read(stored, Person.class);
    assertThat(person.name).isEqualTo("Old");
    assertThat(person.address.street).isEqualTo("Main 1");
    assertThat(person.address.city).isEqualTo("Bonn");
    // written again without "fax", as Jackson 2 does
    assertThat(write(person)).isEqualTo("{\"name\":\"Old\",\"street\":\"Main 1\",\"city\":\"Bonn\"}");
  }

  @Test
  public void shouldStillRejectUnknownPropertyOfClassWithoutUnwrapped() {
    assertThatThrownBy(() -> read("{\"name\":\"x\",\"extra\":1}", Plain.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
    // the unwrapped class read on its own
    assertThatThrownBy(() -> read("{\"street\":\"Main 1\",\"label\":\"x\"}", Spot.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"label\"");
    // a class without @JsonUnwrapped held by a class with @JsonUnwrapped
    assertThatThrownBy(() -> read("{\"name\":\"n\",\"street\":\"s\",\"plain\":{\"name\":\"p\",\"extra\":1}}",
        PersonWithPlain.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
  }

  @Test
  public void shouldReadBackUnwrappedClassThatHoldsItself() {
    Site value = new Site();
    value.name = "Campus";
    value.spot.street = "Rhine 7";
    value.spot.city = "Cologne";
    Site hall = new Site();
    hall.name = "Hall";
    hall.spot.street = "Rhine 9";
    Site annex = new Site();
    annex.name = "Annex";
    annex.spot.city = "Essen";
    value.parts = List.of(hall);
    value.next = annex;
    String json = write(value);
    // Jackson 3.1 alone: Unrecognized property "label" (class ...Site)
    Site site = read(json, Site.class);
    assertThat(site.spot.street).isEqualTo("Rhine 7");
    assertThat(site.parts.get(0).spot.street).isEqualTo("Rhine 9");
    assertThat(site.next.spot.city).isEqualTo("Essen");
    assertThat(write(site)).isEqualTo(json);
  }

  @Test
  public void shouldRejectUnknownPropertyWhereUnwrappingHasNoEffect() {
    // Jackson ignores @JsonUnwrapped on a String, a Map or a List and reads them under their own names
    Tagged tagged = read("{\"name\":\"n\",\"tag\":\"t\",\"attributes\":{\"a\":1},\"items\":[\"i\"]}", Tagged.class);
    assertThat(tagged.tag).isEqualTo("t");
    assertThat(tagged.attributes).containsEntry("a", 1);
    assertThat(tagged.items).containsExactly("i");
    // so an unknown property fails, as in Jackson 2
    assertThatThrownBy(() -> read("{\"name\":\"n\",\"tag\":\"t\",\"extra\":1}", Tagged.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
    // nor on an interface: no deserializer could unwrap it
    assertThatThrownBy(() -> read("{\"name\":\"n\",\"extra\":1}", Badged.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
    // nor where a holder ignores the annotated property: Jackson leaves it out of the deserializer for that holder
    assertThatThrownBy(() -> read("{\"tagged\":{\"name\":\"n\",\"extra\":1}}", TaggedHolder.class))
        .hasStackTraceContaining("UnrecognizedPropertyException").hasStackTraceContaining("\"extra\"");
  }

  @Test
  public void shouldReadInnerClassThatRefersBackToItsOuterClass() {
    // Jackson meets the inner class first in another class
    assertThat(read("{\"desk\":null}", DeskHolder.class).desk).isNull();

    // then the outer class can still create it
    Office office = read("{\"name\":\"Head office\",\"desk\":{\"id\":\"D1\",\"street\":\"Main 1\",\"city\":\"Bonn\"}}",
        Office.class);
    assertThat(office.desk.id).isEqualTo("D1");
    assertThat(office.desk.spot.street).isEqualTo("Main 1");
    assertThat(office.desk.spot.city).isEqualTo("Bonn");
  }

  protected String write(Object value) {
    StringWriter writer = new StringWriter();
    format.getWriter().writeToWriter(writer, format.getMapper().mapJavaToInternal(value));
    return writer.toString();
  }

  protected <T> T read(String json, Class<T> type) {
    Object internal = format.getReader().readInput(new StringReader(json));
    return type.cast(format.getMapper().mapInternalToJava(internal, type.getName()));
  }

  protected Object read(String json, String typeName) {
    Object internal = format.getReader().readInput(new StringReader(json));
    return format.getMapper().mapInternalToJava(internal, typeName);
  }
}
