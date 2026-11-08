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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Cases where Jackson 3 differs from Jackson 2 out of the box and the client's JSON data format keeps the
 * Jackson 2 output. This test is shared with cibseven-external-task-client-jackson2 and runs unchanged on both.
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

  @Test
  public void shouldWriteLocaleWithUnderscore() {
    // Jackson 3 alone writes the language tag "en-US"
    assertThat(format.writeValue(new LocaleHolder())).isEqualTo("{\"locale\":\"en_US\",\"language\":\"de\",\"root\":\"\"}");
  }

  @Test
  public void shouldReadLocaleInBothForms() {
    assertThat(format.readValue("{\"locale\":\"en_US\"}", LocaleHolder.class).locale).isEqualTo(Locale.US);
    assertThat(format.readValue("{\"locale\":\"en-US\"}", LocaleHolder.class).locale).isEqualTo(Locale.US);
  }

  @Test
  public void shouldWriteLocaleWithTypeInformation() {
    assertThat(format.writeValue(new PolymorphicLocale())).isEqualTo("{\"value\":[\"java.util.Locale\",\"en_US\"]}");
  }

  @Test
  public void shouldSkipRootLocaleAsEmpty() {
    // Locale.ROOT is written as "" and counts as empty, so NON_EMPTY leaves it out
    assertThat(format.writeValue(new NonEmptyLocales())).isEqualTo("{\"locale\":\"en_US\"}");
  }

  @Test
  public void shouldWriteStringShapedDatesWithNumericZeroOffset() {
    // Jackson 3 alone writes a zero offset as "Z"
    assertThat(format.writeValue(new FormattedDates()))
        .isEqualTo("{\"date\":\"1970-01-01T00:00:00.000+00:00\",\"calendar\":\"1970-01-01T00:00:00.000+00:00\"}");
  }

  @Test
  public void shouldRejectStringsLongerThan20MillionCharacters() {
    // the string limit of Jackson 2; Jackson 3 alone accepts up to 100 million characters
    String json = "\"" + "a".repeat(20_000_001) + "\"";

    assertThatThrownBy(() -> format.readValue(json, String.class)).hasStackTraceContaining("20000000");
  }
}
