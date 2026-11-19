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
package org.cibseven.bpm.engine.rest.util;

import java.util.List;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.identity.Authentication;

/**
 * Decides whether the first-run setup may create an administrator without authentication.
 *
 * <p>The setup is meant for an engine nobody has configured yet. Whether an administrator
 * exists cannot be told reliably (CVE-2026-77226): administrators can be configured with
 * {@code adminUsers} and {@code adminGroups} or be granted authorizations, so the
 * {@code camunda-admin} group may be empty, or missing, on an engine that is fully
 * administered. The setup is therefore only available while the engine's identity service
 * is writable, it holds no users, groups or authorizations, and no administrators are
 * configured.</p>
 *
 * <p>An engine whose users and groups are kept elsewhere (single sign-on or LDAP in the
 * webclient), which grants no authorizations and configures no administrators, looks like a
 * fresh one. Such an installation must configure its administrators.</p>
 */
public class InitialSetupUtil {

  public static boolean isSetupAvailable(ProcessEngine processEngine) {
    IdentityService identityService = processEngine.getIdentityService();
    if (identityService.isReadOnly()) {
      return false;
    }

    // without a configuration to inspect, the engine cannot be shown to be unconfigured
    ProcessEngineConfiguration configuration = processEngine.getProcessEngineConfiguration();
    if (!(configuration instanceof ProcessEngineConfigurationImpl)
        || hasConfiguredAdministrators((ProcessEngineConfigurationImpl) configuration)) {
      return false;
    }

    // the queries must see everything, whoever is calling
    Authentication currentAuthentication = identityService.getCurrentAuthentication();
    identityService.clearAuthentication();
    try {
      return identityService.createUserQuery().count() == 0
          && identityService.createGroupQuery().count() == 0
          && processEngine.getAuthorizationService().createAuthorizationQuery().count() == 0;
    } finally {
      identityService.setAuthentication(currentAuthentication);
    }
  }

  protected static boolean hasConfiguredAdministrators(ProcessEngineConfigurationImpl configuration) {
    List<String> adminUsers = configuration.getAdminUsers();
    if (adminUsers != null && !adminUsers.isEmpty()) {
      return true;
    }

    // camunda-admin is always among the admin groups, it is the one the setup fills
    List<String> adminGroups = configuration.getAdminGroups();
    if (adminGroups != null) {
      for (String adminGroup : adminGroups) {
        if (!Groups.CAMUNDA_ADMIN.equals(adminGroup)) {
          return true;
        }
      }
    }
    return false;
  }
}
