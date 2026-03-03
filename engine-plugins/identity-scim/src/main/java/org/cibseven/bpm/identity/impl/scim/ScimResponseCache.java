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
package org.cibseven.bpm.identity.impl.scim;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for SCIM GET responses with TTL-based expiration and max size eviction.
 */
public class ScimResponseCache {

  protected final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
  protected final int maxSize;
  protected final long expirationTimeoutMs;

  protected static class CacheEntry {
    final JsonNode response;
    final long createdAt;

    CacheEntry(JsonNode response) {
      this.response = response;
      this.createdAt = System.currentTimeMillis();
    }

    boolean isExpired(long timeoutMs) {
      return System.currentTimeMillis() - createdAt > timeoutMs;
    }
  }

  public ScimResponseCache(int maxSize, long expirationTimeoutMin) {
    this.maxSize = maxSize;
    this.expirationTimeoutMs = expirationTimeoutMin * 60 * 1000;
  }

  /**
   * Get a cached response by URL key. Returns null if not found or expired.
   */
  public JsonNode get(String key) {
    CacheEntry entry = cache.get(key);
    if (entry != null) {
      if (!entry.isExpired(expirationTimeoutMs)) {
        return entry.response;
      }
      cache.remove(key);
    }
    return null;
  }

  /**
   * Cache a response for the given URL key.
   */
  public void put(String key, JsonNode response) {
    if (response == null) {
      return;
    }
    evictExpired();
    if (cache.size() >= maxSize) {
      evictOldest();
    }
    cache.put(key, new CacheEntry(response));
  }

  /**
   * Invalidate all cache entries whose key contains the given endpoint path.
   */
  public void invalidate(String endpointPath) {
    cache.keySet().removeIf(key -> key.contains(endpointPath));
  }

  /**
   * Invalidate all cache entries.
   */
  public void invalidateAll() {
    cache.clear();
  }

  /**
   * Get the current number of cached entries.
   */
  public int size() {
    return cache.size();
  }

  protected void evictExpired() {
    cache.entrySet().removeIf(entry -> entry.getValue().isExpired(expirationTimeoutMs));
  }

  protected void evictOldest() {
    cache.entrySet().stream()
        .min((a, b) -> Long.compare(a.getValue().createdAt, b.getValue().createdAt))
        .ifPresent(oldest -> cache.remove(oldest.getKey(), oldest.getValue()));
  }
}
