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
import java.util.StringJoiner;

import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.QueryOrderingProperty;

/**
 * The cache key includes the pagination window (firstResult, maxResults) and the ordering, because
 * those determine which slice of results is returned. Without them, page 2 of a query would hit the
 * cached page 1 and return the wrong rows.
 * <p>
 * Note: caching paginated slices of an externally-mutable, ordered collection can still skew across
 * pages if entries are added/removed/reordered in LDAP between two page fetches (the pages were cached
 * at different times). This window is bounded by the TTL; keep it short if exact cross-page consistency
 * matters. Passwords are intentionally not cached; {@link #checkPassword} always hits LDAP.
 */
public class CachingLdapIdentityProviderSession extends LdapIdentityProviderSession {

  protected final LdapCacheStore cacheStore;

  public CachingLdapIdentityProviderSession(LdapConfiguration ldapConfiguration, LdapCacheStore cacheStore) {
    super(ldapConfiguration);
    this.cacheStore = cacheStore;
  }

  @Override
  protected List<User> findUsersByGroupId(LdapUserQueryImpl query) {
    return cacheStore.cached(cacheStore.getUserByGroupCache(), keyForUserQuery(null, query), "findUsersByGroupId",
        () -> super.findUsersByGroupId(query));
  }

  @Override
  public List<User> findUsersWithoutGroupId(LdapUserQueryImpl query, String userBaseDn, boolean ignorePagination) {
    return cacheStore.cached(cacheStore.getUserQueriesCache(), keyForUserQuery(userBaseDn, query, ignorePagination),
        "findUsersWithoutGroupId", () -> super.findUsersWithoutGroupId(query, userBaseDn, ignorePagination));
  }

  @Override
  public boolean checkPassword(String userId, String password) {
    // Passwords are never cached; always authenticate against LDAP.
    return super.checkPassword(userId, password);
  }

  @Override
  public List<Group> findGroupByQueryCriteria(LdapGroupQuery query) {
    String key = new StringJoiner("::")
        .add(nullable(query.getId()))
        .add(arrayKey(query.getIds()))
        .add(nullable(query.getName()))
        .add(nullable(query.getNameLike()))
        .add(nullable(query.getType()))
        .add(nullable(query.getUserId()))
        .add(nullable(query.getTenantId()))
        .add(paginationKey(query.getFirstResult(), query.getMaxResults()))
        .add(orderByKey(query.getOrderingProperties()))
        .toString();
    return cacheStore.cached(cacheStore.getGroupQueriesCache(), key, "findGroupByQueryCriteria",
        () -> super.findGroupByQueryCriteria(query));
  }

  private static String keyForUserQuery(String userBaseDn, LdapUserQueryImpl query) {
    return keyForUserQuery(userBaseDn, query, false);
  }

  private static String keyForUserQuery(String userBaseDn, LdapUserQueryImpl query, boolean ignorePagination) {
    StringJoiner key = new StringJoiner("::")
        .add(userBaseDn == null ? "" : userBaseDn + "^")
        .add(nullable(query.getId()))
        .add(arrayKey(query.getIds()))
        .add(nullable(query.getEmail()))
        .add(nullable(query.getEmailLike()))
        .add(nullable(query.getFirstName()))
        .add(nullable(query.getFirstNameLike()))
        .add(nullable(query.getLastName()))
        .add(nullable(query.getLastNameLike()))
        .add(nullable(query.getGroupId()))
        .add(nullable(query.getTenantId()))
        .add(orderByKey(query.getOrderingProperties()));
    // When pagination is ignored the full set is returned, so the window must NOT distinguish keys.
    key.add(ignorePagination ? "ALL" : paginationKey(query.getFirstResult(), query.getMaxResults()));
    return key.toString();
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static String arrayKey(String[] values) {
    if (values == null) {
      return "";
    }
    StringJoiner joiner = new StringJoiner(",", "[", "]");
    for (String value : values) {
      joiner.add(value);
    }
    return joiner.toString();
  }

  private static String paginationKey(int firstResult, int maxResults) {
    return firstResult + "/" + maxResults;
  }

  private static String orderByKey(List<QueryOrderingProperty> orderingProperties) {
    if (orderingProperties == null || orderingProperties.isEmpty()) {
      return "";
    }
    StringJoiner joiner = new StringJoiner(",", "{", "}");
    for (QueryOrderingProperty property : orderingProperties) {
      joiner.add(String.valueOf(property));
    }
    return joiner.toString();
  }
}
