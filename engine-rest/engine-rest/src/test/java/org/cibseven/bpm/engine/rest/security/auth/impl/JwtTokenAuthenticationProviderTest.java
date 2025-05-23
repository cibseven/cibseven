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

import java.util.Base64;
import java.util.Date;

import javax.servlet.ServletException;

import org.cibseven.bpm.engine.rest.helper.MockProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.JwtUser;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.IOException;
import io.jsonwebtoken.security.InvalidKeyException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

public class JwtTokenAuthenticationProviderTest {

  @Test
  public void testJwtAuthentication()
      throws IOException, ServletException, WeakKeyException, InvalidKeyException, JsonProcessingException {
    MockHttpServletRequest request = new MockHttpServletRequest();

    applyFilter(request, MockProvider.EXAMPLE_USER_ID);

    JwtTokenAuthenticationProvider authenticationProvider = new JwtTokenAuthenticationProvider();
    AuthenticationResult authenticatedUser = authenticationProvider.extractAuthenticatedUser(request, null);

    Assert.assertTrue(authenticatedUser.isAuthenticated());
    Assert.assertEquals(MockProvider.EXAMPLE_USER_ID, authenticatedUser.getAuthenticatedUser());

  }

  protected void applyFilter(MockHttpServletRequest request, String username)
      throws IOException, ServletException, WeakKeyException, InvalidKeyException, JsonProcessingException {

    JwtUser user = new JwtUser(username);

    String jwtSecret = Configuration.getInstance().getSecret();
    long validInMillis = 1000L * 60L * 100L; // 100 minutes

    String token = createToken(jwtSecret, validInMillis, user);

    request.addHeader(JwtTokenAuthenticationProvider.AUTHORIZATION_HEADER,
        JwtTokenAuthenticationProvider.BEARER_PREFIX + token);

  }

  private String createToken(String jwtTokenSecret, long validInMillis, JwtUser user)
      throws WeakKeyException, InvalidKeyException, JsonProcessingException {
    return JwtTokenAuthenticationProvider.BEARER_PREFIX + Jwts.builder().claims().subject(user.getUserID())
        .expiration(new Date(System.currentTimeMillis() + validInMillis)).issuedAt(new Date())
        .add("user", serialize(user)).and()
        .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtTokenSecret.getBytes()))).compact();
  };

  private String serialize(JwtUser user) throws JsonProcessingException {
    return new ObjectMapper().writeValueAsString(user);
  }

}
