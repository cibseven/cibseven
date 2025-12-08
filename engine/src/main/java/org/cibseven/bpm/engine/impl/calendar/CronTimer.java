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
 * 
 * Modifications Copyright 2025 CIB software GmbH
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
        // ================= LEGACY COMPATIBILITY PATCH =================
        // Migrating legacy Quartz 1.8.4 cron expressions to latest cron-utils (Quartz >=2.x).
        // Examples:
        // "0 0 0 * * 5L" -> "0 0 0 ? * 5L" (last Friday of month)
        // "0 0 0 1W * *" -> "0 0 0 1W * ?" (weekday nearest to 1st)
        // "0 0 0 * * THUL" -> "0 0 0 ? * THUL" (last Thursday)
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
  public static String patchLegacyCronExpression(final String expression) {
    final String[] parts = expression.split(" ");
    if (parts.length < 6) {
      return expression; // Not a valid Quartz cron expression
    }
    // Quartz 1.8.4 allowed both day-of-month and day-of-week to be set in some cases,
    // but modern Quartz (and cron-utils) require one to be '?'.
    // See: https://github.com/quartz-scheduler/quartz/blob/quartz-1.8.4/core/src/main/java/org/quartz/CronExpression.java
    final String dayOfMonth = parts[3];
    final String dayOfWeek = parts[5];
    boolean domSet = dayOfMonth != null && !"?".equals(dayOfMonth);
    boolean dowSet = dayOfWeek != null && !"?".equals(dayOfWeek);
    if (domSet && dowSet) {
      // Only patch for legacy Quartz patterns:
      // - If day-of-week contains 'L' (e.g. '5L', 'THUL'), clear day-of-month
      // - If day-of-month contains 'W' (e.g. '1W'), clear day-of-week
      // - If day-of-week contains '#', clear day-of-month
      // - Otherwise, default to clearing day-of-month
      if (dayOfWeek.matches(".*[0-9]L$|.*[A-Z]{3}L$")) {
        parts[3] = "?";
      } else if (dayOfMonth.contains("W")) {
        parts[5] = "?";
      } else if (dayOfWeek.contains("#")) {
        parts[3] = "?";
      } else {
        parts[3] = "?";
      }
      return String.join(" ", parts);
    }
    return expression;
  }
}
