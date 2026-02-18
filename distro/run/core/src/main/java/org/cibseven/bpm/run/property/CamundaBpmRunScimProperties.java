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
package org.cibseven.bpm.run.property;

import org.cibseven.bpm.identity.impl.scim.plugin.ScimIdentityProviderPlugin;

public class CamundaBpmRunScimProperties extends ScimIdentityProviderPlugin {

  public static final String PREFIX = CamundaBpmRunProperties.PREFIX + ".scim";

  boolean enabled = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public String toString() {
    return "CamundaBpmRunScimProperty [enabled=" + enabled +
        ", scimVersion=" + scimVersion +
        ", serverUrl=******" + // sensitive for logging
        ", authenticationType=******" + // sensitive for logging
        ", username=******" + // sensitive for logging
        ", password=******" + // sensitive for logging
        ", bearerToken=******" + // sensitive for logging
        ", oauth2TokenUrl=******" + // sensitive for logging
        ", oauth2ClientId=******" + // sensitive for logging
        ", oauth2ClientSecret=******" + // sensitive for logging
        ", oauth2Scope=******" + // sensitive for logging
        ", usersEndpoint=" + usersEndpoint +
        ", groupsEndpoint=" + groupsEndpoint +
        ", userBaseFilter=" + userBaseFilter +
        ", groupBaseFilter=" + groupBaseFilter +
        ", userIdAttribute=" + userIdAttribute +
        ", userFirstnameAttribute=" + userFirstnameAttribute +
        ", userLastnameAttribute=" + userLastnameAttribute +
        ", userPasswordAttribute=" + userPasswordAttribute +
        ", userEmailAttribute=" + userEmailAttribute +
        ", groupIdAttribute=" + groupIdAttribute +
        ", groupNameAttribute=" + groupNameAttribute +
        ", groupMembersAttribute=" + groupMembersAttribute +
        ", allowModifications=" + allowModifications +
        ", connectionTimeout=" + connectionTimeout +
        ", useSsl=" + useSsl +
        ", acceptUntrustedCertificates=" + acceptUntrustedCertificates +
        ", pageSize=" + pageSize +
        ", authorizationCheckEnabled=" + authorizationCheckEnabled +
        ", scimAuthenticationEnabled=" + scimAuthenticationEnabled + "]";
  }
}