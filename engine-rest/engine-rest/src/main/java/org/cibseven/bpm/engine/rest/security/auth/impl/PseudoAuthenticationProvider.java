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
package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * <p>
 * Authenticates a user through userid from custom header.
 * </p>
 *
 * @author Patrick Fincke
 */
public class PseudoAuthenticationProvider implements AuthenticationProvider {

  protected static final String USER_ID_HEADER = "Context-User-ID";

  @Override
  public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request,
      ProcessEngine engine) {
    String userIdHeader = request.getHeader(USER_ID_HEADER);
    return AuthenticationResult.successful(userIdHeader);
  }

  @Override
  public void augmentResponseByAuthenticationChallenge(HttpServletResponse response, ProcessEngine engine) {
  }

}