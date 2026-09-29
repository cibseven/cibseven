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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.identity.impl.ldap.util.LdapPluginLogger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Holds the caches backing {@link CachingLdapIdentityProviderSession}
 * Each lookup type has its own cache, sized independently:
 * <ul>
 *   <li><b>userQueriesCache</b> backs name/email/id searches. High-cardinality keys (many distinct
 *       filters, multiplied by pagination and ordering), each hit rarely &rarr; large maximum weight,
 *       shorter TTL.</li>
 *   <li><b>userByGroupCache</b> and <b>groupQueriesCache</b> back "members of a group" / group
 *       lookups. Low-cardinality keys hit repeatedly (page renders, authorization checks) &rarr;
 *       small maximum weight, longer TTL.</li>
 * </ul>
 * Each cached entry expires after it was written (expireAfterWrite), so entries are evicted
 * individually based on their own age. Caches are bounded by a maximum <em>weight</em> rather than a
 * maximum entry count: an entry weighs as many units as the list it holds has elements (with a floor
 * of 1 so empty results still count). This caps memory by the number of cached domain objects, so a
 * single broad query returning thousands of users cannot blow up the heap the way an entry-count
 * bound would allow.
 */
public class LdapCacheStore {

  // High-cardinality, rarely-repeated name/email/id searches: larger, shorter-lived.
  protected final Cache<String, List<User>> userQueriesCache;
  // Low-cardinality, frequently-repeated group lookups: smaller, longer-lived.
  protected final Cache<String, List<User>> userByGroupCache;
  protected final Cache<String, List<Group>> groupQueriesCache;

  protected final LdapCacheStats stats = new LdapCacheStats();

  // Periodic INFO logging of the stats summary. Debug aid; controlled by configuration.
  protected final boolean statsLogEnabled;
  protected final long statsLogInterval;
  protected final AtomicLong lookupCount = new AtomicLong();

  public LdapCacheStore(LdapConfiguration config) {
    this.userQueriesCache = build(config.getCacheUserQueriesTtlSeconds(), config.getCacheUserQueriesMaxWeight());
    this.userByGroupCache = build(config.getCacheGroupTtlSeconds(), config.getCacheGroupMaxWeight());
    this.groupQueriesCache = build(config.getCacheGroupTtlSeconds(), config.getCacheGroupMaxWeight());
    this.statsLogEnabled = config.isCacheStatsLogEnabled();
    // Guard against a non-positive interval so the modulo below never divides by zero.
    this.statsLogInterval = Math.max(1, config.getCacheStatsLogInterval());
  }

  /**
   * Builds a cache bounded by total weight, where an entry weighs {@code max(1, list.size())} units.
   * Weighing by element count caps memory against the number of cached domain objects rather than the
   * number of distinct queries, so one broad result cannot dominate the heap.
   */
  protected static <K, E> Cache<K, List<E>> build(long ttlSeconds, long maxWeight) {
    return Caffeine.newBuilder()
        .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
        .maximumWeight(maxWeight)
        .<K, List<E>>weigher((key, value) -> Math.max(1, value.size()))
        .build();
  }

  public Cache<String, List<User>> getUserQueriesCache() {
    return userQueriesCache;
  }

  public Cache<String, List<User>> getUserByGroupCache() {
    return userByGroupCache;
  }

  public Cache<String, List<Group>> getGroupQueriesCache() {
    return groupQueriesCache;
  }

  public LdapCacheStats getStats() {
    return stats;
  }

  /**
   * Looks up {@code key} in {@code cache}, recording cache statistics. On a miss the {@code loader}
   * (the real LDAP fetch) is run and timed; that duration calibrates the per-hit "time saved" estimate.
   */
  public <V> V cached(Cache<String, V> cache, String key, String method, Supplier<V> loader) {
    LdapCacheStats.MethodStats methodStats = stats.forMethod(method);
    // The loader only runs on a miss; this flag lets us tell a hit from a miss after get() returns.
    AtomicBoolean missed = new AtomicBoolean(false);
    V value = cache.get(key, k -> {
      missed.set(true);
      long start = System.nanoTime();
      V loaded = loader.get();
      methodStats.recordMiss(System.nanoTime() - start);
      return loaded;
    });
    if (!missed.get()) {
      methodStats.recordHit();
    }
    logStats();
    return value;
  }

  /**
   * Emits the cache stats summary at INFO every {@code statsLogInterval} lookups when enabled.
   * The snapshot is only built on the logging tick, so the common path stays a single atomic
   * increment plus a comparison.
   */
  protected void logStats() {
    if (!statsLogEnabled) {
      return;
    }
    if (lookupCount.incrementAndGet() % statsLogInterval == 0) {
      LdapPluginLogger.INSTANCE.cacheStats(stats.summaryLine());
    }
  }
}
