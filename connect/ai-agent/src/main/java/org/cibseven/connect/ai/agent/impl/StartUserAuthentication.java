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
package org.cibseven.connect.ai.agent.impl;

import java.util.List;

import org.cibseven.bpm.engine.impl.identity.Authentication;

/**
 * An {@link Authentication} the connector built from the start user of a
 * process instance, because no user was authenticated on the thread.
 *
 * <p>The type is the marker. Its tenant list is merely the instance's own
 * tenant, so it proves nothing about membership — and it stays recognisable
 * after {@code ProcessStarterTool} has put it on a worker thread, where an
 * agent task in a child process would otherwise take it for a real login.
 */
final class StartUserAuthentication extends Authentication {

  StartUserAuthentication(String userId, List<String> groupIds, List<String> tenantIds) {
    super(userId, groupIds, tenantIds);
  }
}
