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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public class TimeHolder {
  private LocalDate day;
  private LocalDateTime at;
  private Instant instant;
  private Duration duration;

  public LocalDate getDay() { return day; }
  public void setDay(LocalDate day) { this.day = day; }
  public LocalDateTime getAt() { return at; }
  public void setAt(LocalDateTime at) { this.at = at; }
  public Instant getInstant() { return instant; }
  public void setInstant(Instant instant) { this.instant = instant; }
  public Duration getDuration() { return duration; }
  public void setDuration(Duration duration) { this.duration = duration; }

  @Override
  public boolean equals(Object o) {
    return o instanceof TimeHolder t && Objects.equals(day, t.day) && Objects.equals(at, t.at)
        && Objects.equals(instant, t.instant) && Objects.equals(duration, t.duration);
  }

  @Override
  public int hashCode() { return Objects.hash(day, at, instant, duration); }

  @Override
  public String toString() { return "TimeHolder[" + day + "," + at + "," + instant + "," + duration + "]"; }
}
