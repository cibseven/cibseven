package org.cibseven.bpm.spring.boot.starter.security.oauth2.impl;

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

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cibseven.bpm.engine.rest.security.auth.impl.HttpBasicAuthenticationProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CompositeAuthenticationProvider tries to authenticate using the first
 * provider. If the first provider fails, it falls back to the second provider.
 */
public class CompositeOAuth2AuthenticationProvider implements AuthenticationProvider {
  
  private final AuthenticationProvider primaryProvider;
  private final AuthenticationProvider fallbackProvider;

  /**
   * Default constructor using JwtTokenAuthenticationProvider as primary and
   * HttpBasicAuthenticationProvider as fallback.
   */
  public CompositeOAuth2AuthenticationProvider() {
    this(new OAuth2AuthenticationProvider(), new HttpBasicAuthenticationProvider());
  }
  
  public CompositeOAuth2AuthenticationProvider(AuthenticationProvider primaryProvider, AuthenticationProvider fallbackProvider) {
    this.primaryProvider = primaryProvider;
    this.fallbackProvider = fallbackProvider;
  }

  @Override
  public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
    
    // Try primary provider
    AuthenticationResult result = primaryProvider.extractAuthenticatedUser(request, engine);
    
    if (result.isAuthenticated()) {
      return result;
    }

    // Fallback to secondary provider
    return fallbackProvider.extractAuthenticatedUser(request, engine);
  }

  @Override
  public void augmentResponseByAuthenticationChallenge(HttpServletResponse response, ProcessEngine engine) {
    // Delegate to both providers
    primaryProvider.augmentResponseByAuthenticationChallenge(response, engine);
    fallbackProvider.augmentResponseByAuthenticationChallenge(response, engine);
  }
}
