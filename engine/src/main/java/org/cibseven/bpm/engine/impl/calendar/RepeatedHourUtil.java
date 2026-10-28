/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.impl.calendar;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.Date;

/**
 * When clocks go back (e.g. 03:00 CEST to 02:00 CET), the local times of the repeated hour occur twice.
 * Due dates are persisted and compared with the current time as local time without offset, so a due date
 * in the second pass (e.g. 02:00 CET) looks due during the first pass (e.g. at 02:45 CEST). The timer fires
 * at once, its next due date falls into the second pass again and the timer fires in a loop until the
 * clocks have gone back. Like the Quartz based implementation used before, timer cycles skip due dates
 * in the second pass of the repeated hour.
 */
public final class RepeatedHourUtil {

  private RepeatedHourUtil() {
  }

  /**
   * @return the first instant after the repeated hour (e.g. 03:00 CET) if the date lies in the second pass
   *         of a repeated hour of the JVM default time zone, otherwise <code>null</code>
   */
  public static Date getEndOfRepeatedHour(Date date) {
    if (date == null) {
      return null;
    }
    ZonedDateTime dateTime = ZonedDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    ZoneOffsetTransition transition = dateTime.getZone().getRules().getTransition(dateTime.toLocalDateTime());
    // Only the second pass of an overlap must be skipped: in the first pass (offset before the transition)
    // the local times are not yet repeated, so they are safe. The second pass (offset after the transition)
    // repeats them and would look already due.
    if (transition == null || !transition.isOverlap() || !dateTime.getOffset().equals(transition.getOffsetAfter())) {
      return null;
    }
    // the local time before the transition (e.g. 03:00) is reached again with the offset after it (e.g. 03:00 CET)
    return Date.from(transition.getDateTimeBefore().toInstant(transition.getOffsetAfter()));
  }

  public static boolean isInSecondPassOfRepeatedHour(Date date) {
    return getEndOfRepeatedHour(date) != null;
  }

}
