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
  public static String patchLegacyCronExpression(final String expression) {
    final String[] parts = expression.split(" ");
    if (parts.length < 6) {
      return expression; // Not a valid Quartz cron expression
    }
    
    // Migration Guide: Quartz 1.8.4 → 2.5.0:
    // 1. Both Day-of-Month and Day-of-Week are specified with concrete values (neither '?' nor '*')
    // 2. Both fields are set to '*' (ambiguous scheduling - could match any day)
    // 3. Both fields are set to '?' (no scheduling criteria specified)
    final String dayOfMonth = parts[3];
    final String dayOfWeek = parts[5];
    
    // Check if field is set (not a wildcard '?' or '*')
    boolean domSet = dayOfMonth != null && !"?".equals(dayOfMonth) && !"*".equals(dayOfMonth);
    boolean dowSet = dayOfWeek != null && !"?".equals(dayOfWeek) && !"*".equals(dayOfWeek);
    
    if (domSet && dowSet) {
      // Both fields are set - this is invalid
      // Apply migration logic based on special characters to determine which field to keep:

      // Priority 1: If day-of-week has 'L' modifier (last occurrence), keep day-of-week
      if (dayOfWeek.matches(".*[0-9]L$|.*[A-Z]{3}L$")) {
        parts[3] = "?"; // Clear day-of-month
      } 
      // Priority 2: If day-of-month has 'W' modifier (weekday), keep day-of-month  
      else if (dayOfMonth.contains("W")) {
        parts[5] = "?"; // Clear day-of-week
      } 
      // Priority 3: If day-of-week has '#' modifier (nth occurrence), keep day-of-week
      else if (dayOfWeek.contains("#")) {
        parts[3] = "?"; // Clear day-of-month
      } 
      // Default: Clear day-of-month (preserve day-of-week scheduling)
      else {
        parts[3] = "?";
      }
      return String.join(" ", parts);
    }
    
    // Convert '*' to '?' when the other field is set
    if ("*".equals(dayOfMonth) && dowSet) {
      parts[3] = "?"; // Convert day-of-month from '*' to '?'
      return String.join(" ", parts);
    }
    if ("*".equals(dayOfWeek) && domSet) {
      parts[5] = "?"; // Convert day-of-week from '*' to '?'
      return String.join(" ", parts);
    }
    
    // Both fields cannot be '*' - convert day-of-week to '?'
    if ("*".equals(dayOfMonth) && "*".equals(dayOfWeek)) {
      parts[5] = "?";
      return String.join(" ", parts);
    }
    // Both fields cannot be '?' - fix by keeping day-of-week as '?'
    if ("?".equals(dayOfMonth) && "?".equals(dayOfWeek)) {
      parts[3] = "*";
      return String.join(" ", parts);
    }
    return expression;
  }
}
