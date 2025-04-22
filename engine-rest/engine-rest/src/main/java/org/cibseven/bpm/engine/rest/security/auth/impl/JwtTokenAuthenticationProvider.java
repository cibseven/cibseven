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

import java.io.IOException;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cibseven.bpm.engine.AuthenticationException;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.JwtUser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JwtTokenAuthenticationProvider implements AuthenticationProvider {
  
  public static final String AUTHORIZATION_HEADER = "Authorization";
  public static final String BEARER_PREFIX = "Bearer ";
    
  private Configuration configuration;
  
  @Override
  public void augmentResponseByAuthenticationChallenge(HttpServletResponse response, ProcessEngine engine) { }

  @Override
  public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
    try {
      configuration = Configuration.getInstance();
      HttpServletRequest rq = (HttpServletRequest) request;
      
      String authHeader = rq.getHeader(AUTHORIZATION_HEADER);
      if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
        return AuthenticationResult.unsuccessful();
      }
      
      JwtUser user = parse(authHeader, configuration.getSecret());
      
      return AuthenticationResult.successful(user.getUserID());
      
    } catch (AuthenticationException e) {
      return AuthenticationResult.unsuccessful();
    }
  }
  
  public JwtUser parse(String tokenHeader, String jwtTokenSecret) {
    try {
      String token = tokenHeader.replace(BEARER_PREFIX, "");
      SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtTokenSecret));
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      JwtUser user = deserialize((String) claims.get("user"));
      return user;
    } catch (ExpiredJwtException x) {
      throw new AuthenticationException(tokenHeader, x.getMessage());
    } catch (JwtException x) {
      throw new AuthenticationException(tokenHeader, x.getMessage());
    }
  }
  
  private JwtUser deserialize(String json) {
    try {
      ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      JwtUser user = mapper.readValue(json, JwtUser.class);
      return user;
    } catch (IllegalArgumentException x) {
      throw new AuthenticationException(json, x.getMessage());
    } catch (IOException x) {
      throw new AuthenticationException(json, x.getMessage());
    }
  }
  
}
