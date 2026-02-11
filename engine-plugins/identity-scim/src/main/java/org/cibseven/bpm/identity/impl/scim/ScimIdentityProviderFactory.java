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

import org.cibseven.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.cibseven.bpm.engine.impl.identity.WritableIdentityProvider;
import org.cibseven.bpm.engine.impl.interceptor.Session;
import org.cibseven.bpm.engine.impl.interceptor.SessionFactory;

/**
 * Factory for SCIM Identity Provider Sessions.
 */
public class ScimIdentityProviderFactory implements SessionFactory {

  protected ScimConfiguration scimConfiguration;

  @Override
  public Class<?> getSessionType() {
    if (scimConfiguration != null && !scimConfiguration.isReadOnlyAccess()) {
      return WritableIdentityProvider.class;
    } else {
      return ReadOnlyIdentityProvider.class;
    }
  }

  @Override
  public Session openSession() {
    if (scimConfiguration != null && !scimConfiguration.isReadOnlyAccess()) {
      return new ScimIdentityProviderWritable(scimConfiguration);
    } else {
      return new ScimIdentityProviderReadOnly(scimConfiguration);
    }
  }

  public ScimConfiguration getScimConfiguration() {
    return scimConfiguration;
  }

  public void setScimConfiguration(ScimConfiguration scimConfiguration) {
    this.scimConfiguration = scimConfiguration;
  }
}
