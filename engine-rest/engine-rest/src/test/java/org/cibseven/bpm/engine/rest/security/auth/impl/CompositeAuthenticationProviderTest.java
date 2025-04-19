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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.*;

public class CompositeAuthenticationProviderTest {

    private HttpBasicAuthenticationProvider basicProvider;
    private JwtTokenAuthenticationProvider jwtProvider;
    private CompositeAuthenticationProvider compositeProvider;
    private ProcessEngine mockEngine;

    @Before
    public void setUp() {
        basicProvider = mock(HttpBasicAuthenticationProvider.class);
        jwtProvider = mock(JwtTokenAuthenticationProvider.class);
        compositeProvider = new CompositeAuthenticationProvider(basicProvider, jwtProvider);
        mockEngine = mock(ProcessEngine.class);
    }

    @Test
    public void testPrimaryProviderSucceeds() {
        // Mock the primary provider to succeed
        HttpServletRequest request = new MockHttpServletRequest();
        when(basicProvider.extractAuthenticatedUser(request, mockEngine))
                .thenReturn(AuthenticationResult.successful("basicUser"));

        // Test the composite provider
        AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);

        // Verify the result
        Assert.assertTrue(result.isAuthenticated());
        Assert.assertEquals("basicUser", result.getAuthenticatedUser());

        // Verify that the fallback provider was not called
        verify(jwtProvider, never()).extractAuthenticatedUser(request, mockEngine);
    }

    @Test
    public void testFallbackProviderSucceeds() {
        // Mock the primary provider to fail
        HttpServletRequest request = new MockHttpServletRequest();
        when(basicProvider.extractAuthenticatedUser(request, mockEngine))
                .thenReturn(AuthenticationResult.unsuccessful());

        // Mock the fallback provider to succeed
        when(jwtProvider.extractAuthenticatedUser(request, mockEngine))
                .thenReturn(AuthenticationResult.successful("jwtUser"));

        // Test the composite provider
        AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);

        // Verify the result
        Assert.assertTrue(result.isAuthenticated());
        Assert.assertEquals("jwtUser", result.getAuthenticatedUser());

        // Verify that both providers were called
        verify(basicProvider).extractAuthenticatedUser(request, mockEngine);
        verify(jwtProvider).extractAuthenticatedUser(request, mockEngine);
    }

    @Test
    public void testBothProvidersFail() {
        // Mock both providers to fail
        HttpServletRequest request = new MockHttpServletRequest();
        when(basicProvider.extractAuthenticatedUser(request, mockEngine))
                .thenReturn(AuthenticationResult.unsuccessful());
        when(jwtProvider.extractAuthenticatedUser(request, mockEngine))
                .thenReturn(AuthenticationResult.unsuccessful());

        // Test the composite provider
        AuthenticationResult result = compositeProvider.extractAuthenticatedUser(request, mockEngine);

        // Verify the result
        Assert.assertFalse(result.isAuthenticated());

        // Verify that both providers were called
        verify(basicProvider).extractAuthenticatedUser(request, mockEngine);
        verify(jwtProvider).extractAuthenticatedUser(request, mockEngine);
    }
}
