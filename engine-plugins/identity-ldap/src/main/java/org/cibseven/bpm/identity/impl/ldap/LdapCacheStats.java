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
package org.cibseven.bpm.identity.impl.ldap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe timing statistics for the LDAP cache, broken down per method plus an overall summary.
 * <p>
 * "Time saved" cannot be measured directly (a cache hit never runs the real LDAP call, so its true
 * cost is unknown). It is therefore <em>estimated</em>: every cache miss runs the real {@code super.xxx()}
 * call, whose duration we record. Each hit is credited the running average duration of the misses
 * observed for that same method. The estimate self-calibrates as more misses are seen.
 */
public class LdapCacheStats {

  /** One method's counters. All updates are lock-free. */
  public static final class MethodStats {
    private final String method;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong totalMissNanos = new AtomicLong();

    private MethodStats(String method) {
      this.method = method;
    }

    void recordHit() {
      hits.incrementAndGet();
    }

    void recordMiss(long nanos) {
      misses.incrementAndGet();
      totalMissNanos.addAndGet(nanos);
    }

    public String getMethod() {
      return method;
    }

    public long getHits() {
      return hits.get();
    }

    public long getMisses() {
      return misses.get();
    }

    /** Average duration of a real LDAP fetch (a miss) for this method, in milliseconds. */
    public double getAvgMissMillis() {
      long m = misses.get();
      return m == 0 ? 0.0 : (totalMissNanos.get() / (double) m) / 1_000_000.0;
    }

    /** Estimated time saved by hits on this method = hits * avg-miss-duration. */
    public long getEstimatedSavedMillis() {
      return Math.round(getHits() * getAvgMissMillis());
    }

    public double getHitRatio() {
      long total = getHits() + getMisses();
      return total == 0 ? 0.0 : getHits() / (double) total;
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("method", method);
      map.put("hits", getHits());
      map.put("misses", getMisses());
      map.put("hitRatio", round3(getHitRatio()));
      map.put("avgMissMillis", round3(getAvgMissMillis()));
      map.put("estimatedSavedMillis", getEstimatedSavedMillis());
      return map;
    }
  }

  private final Map<String, MethodStats> byMethod = new ConcurrentHashMap<>();

  public MethodStats forMethod(String method) {
    return byMethod.computeIfAbsent(method, MethodStats::new);
  }

  /** A full snapshot suitable for serialization (actuator endpoint, logging). */
  public Map<String, Object> snapshot() {
    long totalHits = 0;
    long totalMisses = 0;
    long totalSavedMillis = 0;

    Map<String, Object> perMethod = new LinkedHashMap<>();
    for (MethodStats stats : byMethod.values()) {
      perMethod.put(stats.getMethod(), stats.toMap());
      totalHits += stats.getHits();
      totalMisses += stats.getMisses();
      totalSavedMillis += stats.getEstimatedSavedMillis();
    }

    long totalRequests = totalHits + totalMisses;

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalHits", totalHits);
    summary.put("totalMisses", totalMisses);
    summary.put("totalRequests", totalRequests);
    summary.put("overallHitRatio", round3(totalRequests == 0 ? 0.0 : totalHits / (double) totalRequests));
    summary.put("estimatedSavedMillis", totalSavedMillis);
    summary.put("estimatedSavedSeconds", round3(totalSavedMillis / 1000.0));
    summary.put("estimatedSavedHuman", humanDuration(totalSavedMillis));

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("summary", summary);
    root.put("methods", perMethod);
    return root;
  }

  /** A one-line human-readable summary, e.g. for periodic logging. */
  @SuppressWarnings("unchecked")
  public String summaryLine() {
    Map<String, Object> summary = (Map<String, Object>) snapshot().get("summary");
    return String.format("LDAP cache: %s requests, %s hits (%.1f%%), ~%s saved since start",
        summary.get("totalRequests"),
        summary.get("totalHits"),
        ((Number) summary.get("overallHitRatio")).doubleValue() * 100.0,
        humanDuration((Long) summary.get("estimatedSavedMillis")));
  }

  static String humanDuration(long millis) {
    if (millis < 1000) {
      return millis + " ms";
    }
    double seconds = millis / 1000.0;
    if (seconds < 60) {
      return String.format("%.1f s", seconds);
    }
    long totalSeconds = millis / 1000;
    long minutes = totalSeconds / 60;
    long remSeconds = totalSeconds % 60;
    if (minutes < 60) {
      return String.format("%d min %d s", minutes, remSeconds);
    }
    long hours = minutes / 60;
    long remMinutes = minutes % 60;
    return String.format("%d h %d min", hours, remMinutes);
  }

  private static double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }
}
