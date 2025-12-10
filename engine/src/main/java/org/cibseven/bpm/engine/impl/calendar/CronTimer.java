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

/**
 * A cron timer implementation that uses cronutils library for parsing and evaluation.
 */
public class CronTimer {

  private final static EngineUtilLogger LOG = ProcessEngineLogger.UTIL_LOGGER;

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
    final String[] parts = expression.split(" ");
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
    return String.join(" ", parts);
  }
}
