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

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Month;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

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

  protected String write(Object value) {
    StringWriter writer = new StringWriter();
    format.getWriter().writeToWriter(writer, format.getMapper().mapJavaToInternal(value));
    return writer.toString();
  }

  protected <T> T read(String json, Class<T> type) {
    Object internal = format.getReader().readInput(new StringReader(json));
    return type.cast(format.getMapper().mapInternalToJava(internal, type.getName()));
  }
}
