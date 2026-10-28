/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.impl.calendar;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.util.EngineUtilLogger;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cron timer implementation that uses cronutils library for parsing and evaluation.
 */
public class CronTimer {

  private final static EngineUtilLogger LOG = ProcessEngineLogger.UTIL_LOGGER;

  // matches '<start>/<increment>' where start is a value, a name or a range, e.g. '0/60', 'MON/8' or '0-30/60'
  private static final Pattern INCREMENT_PATTERN = Pattern.compile("([^/]+)/(\\d+)");
  // max values of second, minute, hour, day-of-month, month, day-of-week and year in Quartz
  private static final int[] MAX_FIELD_VALUES = { 59, 59, 23, 31, 12, 7, 2099 };

  protected final Cron cron;

  public CronTimer(final Cron cron) {
    this.cron = cron;
  }

  public Date getDueDate(final Date afterTime) {

    long fromEpochMilli = afterTime.getTime();
    final var next = ExecutionTime.forCron(cron)
        .nextExecution(ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromEpochMilli), ZoneId.systemDefault()))
        .map(ZonedDateTime::toInstant)
        .map(Instant::toEpochMilli);

    return new Date(next.orElse(fromEpochMilli));
  }

  public static CronTimer parse(final String text, final CronType cronType, final boolean supportLegacyQuartzSyntax) throws ParseException {
    try {
      String expression = text;
      if (cronType == CronType.QUARTZ && supportLegacyQuartzSyntax) {
        String originalExpression = expression;
        // Migrating legacy Quartz 1.8.4 cron expressions to Quartz 2.5.0 (cron-utils).
        expression = patchLegacyCronExpression(expression);
        if (!originalExpression.equals(expression)) {
          LOG.warnLegacyCronExpressionPatched(originalExpression, expression);
        }
      }

      final var cron =
          new CronParser(CronDefinitionBuilder.instanceDefinitionFor(cronType))
              .parse(expression);
      return new CronTimer(cron);
    } catch (final IllegalArgumentException | NullPointerException ex) {
      throw new ParseException(ex.getMessage(), 0);
    }
  }

  /**
   * Patches legacy Quartz 1.8.4 cron expressions for cron-utils compatibility.
   * 
   * @param expression the original cron expression
   * @return the patched expression, or unchanged if no conflict
   */
  private static String patchLegacyCronExpression(final String expression) {
    // Quartz 1.8.4 accepted any whitespace (spaces or tabs) between the fields
    final String[] parts = expression.trim().split("\\s+");
    if (parts.length < 6) {
      return expression; // Not a valid Quartz cron expression
    }
    
    // Migration Guide for Quartz 1.8.4 → 2.5.0
    // Problematic field combinations requiring migration:
    // 1. Both day-of-month and day-of-week are specified with concrete values (neither '?' nor '*')
    // 2. Both fields are set to '*' (ambiguous scheduling - could match any day)
    // 3. Both fields are set to '?' (no scheduling criteria specified)
    final String dayOfMonth = parts[3];
    final String dayOfWeek = parts[5];
    
    // Check if field is set (not a wildcard '*' or '?')
    boolean domSet = dayOfMonth != null && !"?".equals(dayOfMonth) && !"*".equals(dayOfMonth);
    boolean dowSet = dayOfWeek != null && !"?".equals(dayOfWeek) && !"*".equals(dayOfWeek);
    
    if (domSet && dowSet) { // both fields are set
      // Regex matches day-of-week values ending with 'L' (last occurrence), e.g. '5L' or 'FRIL' (last Friday of month)
      if (dayOfWeek.matches(".*[0-9]L$|.*[A-Z]{3}L$")) { // If day-of-week has 'L' modifier (last occurrence), keep day-of-week
        parts[3] = "?"; // clear day-of-month
      } else if (dayOfMonth.contains("W")) { // If day-of-month has 'W' modifier (weekday), keep day-of-month  
        parts[5] = "?"; // clear day-of-week
      } else if (dayOfWeek.contains("#")) { // If day-of-week has '#' modifier (nth occurrence), keep day-of-week
        parts[3] = "?"; // clear day-of-month
      } else {
        parts[3] = "?"; // default: clear day-of-month (preserve day-of-week scheduling)
      }
    } else if ("*".equals(dayOfMonth) && dowSet) { // day-of-month is wildcard, day-of-week is set
      parts[3] = "?"; // convert day-of-month to '?'
    } else if ("*".equals(dayOfWeek) && domSet) { // day-of-week is wildcard, day-of-month is set
      parts[5] = "?"; // convert day-of-week to '?'
    } else if ("*".equals(dayOfMonth) && "*".equals(dayOfWeek)) { // both fields cannot be '*'
      parts[5] = "?"; // convert day-of-week to '?'
    } else if ("?".equals(dayOfMonth) && "?".equals(dayOfWeek)) { // both fields cannot be '?'
      parts[3] = "*"; // convert day-of-month to '*'
    }

    // Increments beyond the range of the field (e.g. '0/60' for minutes) were accepted by Quartz 1.8.4
    // and matched the start value only, so '0 0/60 * * * ?' fired hourly at minute 0.
    // cron-utils rejects them, so the increment is removed: '0/60' -> '0', '0-30/60' -> '0'.
    // A Quartz expression can omit the year, that's why parts.length must be checked as well
    for (int i = 0; i < parts.length && i < MAX_FIELD_VALUES.length; i++) {
      parts[i] = patchIncrementOutOfRange(parts[i], MAX_FIELD_VALUES[i]);
    }
    return String.join(" ", parts);
  }

  private static String patchIncrementOutOfRange(final String field, final int maxValue) {
    final String[] elements = field.split(",", -1);
    for (int i = 0; i < elements.length; i++) {
      final Matcher matcher = INCREMENT_PATTERN.matcher(elements[i]);
      if (matcher.matches() && matcher.group(1).charAt(0) != '*' && isOutOfRange(matcher.group(2), maxValue)) {
        final String start = matcher.group(1);
        final int rangeSeparator = start.indexOf('-');
        elements[i] = rangeSeparator > 0 ? start.substring(0, rangeSeparator) : start;
      }
    }
    return String.join(",", elements);
  }

  private static boolean isOutOfRange(final String increment, final int maxValue) {
    try {
      return Integer.parseInt(increment) > maxValue;
    } catch (final NumberFormatException ex) {
      return true; // too large for an int, so larger than any field maximum
    }
  }

}
