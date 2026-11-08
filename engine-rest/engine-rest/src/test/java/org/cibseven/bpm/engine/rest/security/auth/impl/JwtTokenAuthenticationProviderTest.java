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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.cibseven.bpm.engine.rest.helper.MockProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.JwtUser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.IOException;
import io.jsonwebtoken.security.InvalidKeyException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

public class JwtTokenAuthenticationProviderTest {

  @Test
  public void testJwtAuthentication()
      throws IOException, ServletException, WeakKeyException, InvalidKeyException {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

    applyFilter(request, MockProvider.EXAMPLE_USER_ID);

    JwtTokenAuthenticationProvider authenticationProvider = new JwtTokenAuthenticationProvider();
    AuthenticationResult authenticatedUser = authenticationProvider.extractAuthenticatedUser(request, null);

    Assertions.assertTrue(authenticatedUser.isAuthenticated());
    Assertions.assertEquals(MockProvider.EXAMPLE_USER_ID, authenticatedUser.getAuthenticatedUser());

  }

  protected void applyFilter(HttpServletRequest request, String username)
      throws IOException, ServletException, WeakKeyException, InvalidKeyException {

    JwtUser user = new JwtUser(username);

    String jwtSecret = Configuration.getInstance().getSecret();
    long validInMillis = 1000L * 60L * 100L; // 100 minutes

    String token = createToken(jwtSecret, validInMillis, user);

    String headerValue = JwtTokenAuthenticationProvider.BEARER_PREFIX + token;
    Mockito.when(request.getHeader(JwtTokenAuthenticationProvider.AUTHORIZATION_HEADER)).thenReturn(headerValue);
    Mockito.when(request.getHeaders(JwtTokenAuthenticationProvider.AUTHORIZATION_HEADER)).thenReturn(java.util.Collections.enumeration(java.util.Collections.singletonList(headerValue)));

  }

  private String createToken(String jwtTokenSecret, long validInMillis, JwtUser user)
      throws WeakKeyException, InvalidKeyException {
    return JwtTokenAuthenticationProvider.BEARER_PREFIX + Jwts.builder().claims().subject(user.getUserID())
        .expiration(new Date(System.currentTimeMillis() + validInMillis)).issuedAt(new Date())
        .add("user", serialize(user)).and()
        .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtTokenSecret.getBytes()))).compact();
  };

  private String serialize(JwtUser user) {
    return new JsonMapper().writeValueAsString(user);
  }

  /**
   * Minted by the 2.2 webclient (jjwt-jackson, Jackson 2 user JSON) with a secret of 64 zero bytes,
   * valid until 2126. The 3.0 webclient writes the same JSON apart from the times. The user JSON
   * carries more than {@link JwtUser} knows.
   */
  private static final String WEBCLIENT_2_2_TOKEN =
      "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJkZW1vIiwiZXhwIjo0OTQ3NzU3MjY4LCJpYXQiOjE3OTQxNTcyNjgsInByb2xvbmdhYmxlIjp0cnVlLCJ2ZXJpZnkiOmZhbHNlLCJ1c2VyIjoie1widHlwZVwiOlwib3JnLmNpYnNldmVuLndlYmFwcC5hdXRoLkNJQlVzZXJcIixcInVzZXJJRFwiOlwiZGVtb1wiLFwiZGlzcGxheU5hbWVcIjpcIkTDq21vIDxVc2VyPiAmIFxcXCJDb1xcXCIgPVwiLFwiZW5naW5lXCI6XCJodHRwOi8vb3RoZXJ8L2VuZ2luZS1yZXN0fHJlbW90ZVwifSJ9.gcDRbTjH_31NO-J8Lngq2uPnuzYn8o7xhgz6h-cDb1gLQ9K6S8s7LFmPBaxjm0IzIa_4qaqFn4Y65-kwyO3DXg";

  @Test
  public void readsATokenMintedByThe22Webclient() {
    JwtUser user = new JwtTokenAuthenticationProvider().parse(
        JwtTokenAuthenticationProvider.BEARER_PREFIX + WEBCLIENT_2_2_TOKEN, Base64.getEncoder().encodeToString(new byte[64]));

    Assertions.assertEquals("demo", user.getUserID());
  }

}
