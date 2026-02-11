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
package org.cibseven.bpm.engine.test.api.authorization.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.MissingAuthorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Filip Hrisafov
 */
public class MissingAuthorizationMatcher {

  // Utility method for AssertJ assertions
  public static void assertMissingAuthorizationsEquals(List<MissingAuthorization> actual, List<MissingAuthorization> expected) {
    assertThat(actual)
      .usingElementComparator((a, b) -> {
        if (a == b) return 0;
        if (a == null || b == null) return -1;
        boolean same = org.apache.commons.lang3.StringUtils.equals(a.getResourceId(), b.getResourceId())
          && org.apache.commons.lang3.StringUtils.equals(a.getResourceType(), b.getResourceType())
          && org.apache.commons.lang3.StringUtils.equals(a.getViolatedPermissionName(), b.getViolatedPermissionName());
        return same ? 0 : -1;
      })
      .containsExactlyInAnyOrderElementsOf(expected);
  }

  protected static MissingAuthorization asMissingAuthorization(Authorization authorization) {
    String permissionName = null;
    String resourceId = null;
    String resourceName = null;

    Permission[] permissions = AuthorizationTestUtil.getPermissions(authorization);
    for (Permission permission : permissions) {
      if (permission.getValue() != Permissions.NONE.getValue()) {
        permissionName = permission.getName();
        break;
      }
    }

    resourceId = authorization.getResourceId();

    Resource resource = AuthorizationTestUtil.getResourceByType(authorization.getResourceType());
    resourceName = resource.resourceName();
    return new MissingAuthorization(permissionName, resourceName, resourceId);
  }

  public static List<MissingAuthorization> asMissingAuthorizations(List<Authorization> authorizations) {
    List<MissingAuthorization> missingAuthorizations = new ArrayList<MissingAuthorization>();
    for (Authorization authorization : authorizations) {
      missingAuthorizations.add(asMissingAuthorization(authorization));
    }
    return missingAuthorizations;
  }
}