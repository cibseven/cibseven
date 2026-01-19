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

import java.util.Arrays;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.impl.NamedProcessEngineRestServiceImpl;
import org.cibseven.bpm.engine.rest.ProcessDefinitionRestService;
import org.cibseven.bpm.engine.rest.FilterRestService;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;

/**
 * <p>
 * Authenticates a user through userid from custom header.
 * </p>
 *
 * @author Patrick Fincke
 */
public class PseudoAuthenticationProvider implements AuthenticationProvider {

  protected static final String USER_ID_HEADER = "Context-User-ID";

   // regexes for urls that should be pseudo-authenticated
  protected static final Pattern[] PSEUDO_AUTHENTICATED_URL_PATTERNS = new Pattern[] {
    Pattern.compile("^" + FilterRestService.PATH + "/?"),
    Pattern.compile("^" + ProcessDefinitionRestService.PATH + "/key/?/start"),
    Pattern.compile("^" + ProcessDefinitionRestService.PATH + "/key/?/submit-form"),
    Pattern.compile("^" + NamedProcessEngineRestServiceImpl.PATH + "/?" + FilterRestService.PATH + "/?"),
    Pattern.compile("^" + NamedProcessEngineRestServiceImpl.PATH + "/?" + ProcessDefinitionRestService.PATH + "/key/?/start"),
    Pattern.compile("^" + NamedProcessEngineRestServiceImpl.PATH + "/?" + ProcessDefinitionRestService.PATH + "/key/?/submit-form"),
  };

  @Override
  public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request,
      ProcessEngine engine) {
        
    String userIdHeader = request.getHeader(USER_ID_HEADER);
    if (userIdHeader == null || userIdHeader.isEmpty()) {
      System.out.println("No user id header present, skipping pseudo-authentication");
      return AuthenticationResult.successful(null);
    }
    
    String path = request.getRequestURI().substring(request.getContextPath().length());
    
    if (requiresPseudoAuthentication(path)) {
      System.out.println("Pseudo-authenticating user from header: " + userIdHeader);
      return AuthenticationResult.successful(userIdHeader);
    }

    System.out.println("Request URL does not require pseudo-authentication: " + path);
    return AuthenticationResult.successful(null);

  }

  public static boolean requiresPseudoAuthentication(String requestUrl) {
    return Arrays.stream(PSEUDO_AUTHENTICATED_URL_PATTERNS)
        .anyMatch(pattern -> {
          boolean matches = pattern.matcher(requestUrl).matches();
          System.out.println("Checking pseudo-authentication for pattern " + pattern.pattern() + " and requestUrl " + requestUrl + ": " + matches);
          return matches;
        });
  }

  @Override
  public void augmentResponseByAuthenticationChallenge(HttpServletResponse response, ProcessEngine engine) {
  }

}