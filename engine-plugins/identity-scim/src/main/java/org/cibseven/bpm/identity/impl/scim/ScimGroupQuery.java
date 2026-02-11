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

import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.impl.GroupQueryImpl;
import org.cibseven.bpm.engine.impl.Page;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;

import java.util.List;

/**
 * SCIM Group Query Implementation.
 */
public class ScimGroupQuery extends GroupQueryImpl {

  private static final long serialVersionUID = 1L;

  public ScimGroupQuery() {
    super();
  }

  public ScimGroupQuery(CommandExecutor commandExecutor) {
    super(commandExecutor);
  }

  @Override
  public long executeCount(CommandContext commandContext) {
    final ScimIdentityProviderReadOnly provider = getScimIdentityProvider(commandContext);
    return provider.findGroupCountByQueryCriteria(this);
  }

  @Override
  public List<Group> executeList(CommandContext commandContext, Page page) {
    final ScimIdentityProviderReadOnly provider = getScimIdentityProvider(commandContext);
    return provider.findGroupByQueryCriteria(this);
  }

  protected ScimIdentityProviderReadOnly getScimIdentityProvider(CommandContext commandContext) {
    return (ScimIdentityProviderReadOnly) commandContext.getReadOnlyIdentityProvider();
  }
}
