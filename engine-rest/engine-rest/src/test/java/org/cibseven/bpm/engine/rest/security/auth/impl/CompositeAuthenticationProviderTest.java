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
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.*;

public class CompositeAuthenticationProviderTest {

  private JwtTokenAuthenticationProvider jwtProvider;
  private HttpBasicAuthenticationProvider basicProvider;
  private CompositeAuthenticationProvider compositeProvider;
  private ProcessEngine mockEngine;

  @BeforeEach
  public void setUp() {
    basicProvider = mock(HttpBasicAuthenticationProvider.class);
    jwtProvider = mock(JwtTokenAuthenticationProvider.class);
    compositeProvider = new CompositeAuthenticationProvider(jwtProvider, basicProvider);
    mockEngine = mock(ProcessEngine.class);
  }

  @Test
  public void testPrimaryProviderSucceeds() {
    String expectedUserId = "jwtUser";
    // Mock the primary provider to succeed
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(jwtProvider.extractAuthenticatedUser(request, mockEngine))
        .thenReturn(AuthenticationResult.successful(expectedUserId));
    // Test the composite provider
    AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);
    // Verify the result
    Assertions.assertTrue(result.isAuthenticated());
    Assertions.assertEquals(expectedUserId, result.getAuthenticatedUser());
    // Verify that the fallback provider was not called
    verify(basicProvider, never()).extractAuthenticatedUser(request, mockEngine);
  }

  @Test
  public void testFallbackProviderSucceeds() {
    String expectedUserId = "basicUser";
    // Mock the primary provider to fail
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(jwtProvider.extractAuthenticatedUser(request, mockEngine)).thenReturn(AuthenticationResult.unsuccessful());
    // Mock the fallback provider to succeed
    when(basicProvider.extractAuthenticatedUser(request, mockEngine))
        .thenReturn(AuthenticationResult.successful(expectedUserId));
    // Test the composite provider
    AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);
    // Verify the result
    Assertions.assertTrue(result.isAuthenticated());
    Assertions.assertEquals(expectedUserId, result.getAuthenticatedUser());
    // Verify that both providers were called
    verify(jwtProvider).extractAuthenticatedUser(request, mockEngine);
    verify(basicProvider).extractAuthenticatedUser(request, mockEngine);
  }

  @Test
  public void testBothProvidersFail() {
    // Mock both providers to fail
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(jwtProvider.extractAuthenticatedUser(request, mockEngine)).thenReturn(AuthenticationResult.unsuccessful());
    when(basicProvider.extractAuthenticatedUser(request, mockEngine)).thenReturn(AuthenticationResult.unsuccessful());
    // Test the composite provider
    AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);
    // Verify the result
    Assertions.assertFalse(result.isAuthenticated());
    // Verify that both providers were called
    verify(jwtProvider).extractAuthenticatedUser(request, mockEngine);
    verify(basicProvider).extractAuthenticatedUser(request, mockEngine);
  }
}