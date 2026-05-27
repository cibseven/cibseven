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
package org.cibseven.bpm.client.interceptor.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.cibseven.bpm.client.exception.ExternalTaskClientException;

import java.security.PrivateKey;
import java.util.Date;
import java.util.UUID;

/**
 * <p>Creates signed client assertion JWTs using the JJWT library.</p>
 *
 * <p>The generated assertion conforms to RFC 7523 and can be used with
 * {@link OAuth2ClientCredentialsProvider} for {@code private_key_jwt} client
 * authentication.</p>
 *
 * <p>Each call to {@link #getAssertion()} produces a fresh JWT (new {@code jti},
 * {@code iat}, and {@code exp}). The actual access token is cached by
 * {@link OAuth2ClientCredentialsProvider}; the assertion itself is intentionally
 * short-lived (5 minutes) and regenerated only when a new token is needed.</p>
 *
 * <p>Supported algorithms (auto-detected from key type):</p>
 * <ul>
 *   <li>{@code RS256} – RSA with SHA-256 (RSAPrivateKey, most widely supported)</li>
 *   <li>{@code ES256} – ECDSA with P-256 and SHA-256 (ECPrivateKey on P-256 curve)</li>
 * </ul>
 *
 * <p>Requires {@code io.jsonwebtoken:jjwt-api} at compile time and
 * {@code io.jsonwebtoken:jjwt-impl} at runtime.</p>
 *
 */
public class JjwtClientAssertionProvider implements ClientAssertionProvider {

  /** JWT lifetime in milliseconds (5 minutes). */
  protected static final long ASSERTION_LIFETIME_MS = 5 * 60 * 1000L;

  protected final String clientId;
  protected final String audience;
  protected final PrivateKey privateKey;

  /**
   * Creates a provider that signs assertions with any supported private key.
   * JJWT auto-detects the algorithm based on key type (RS256 for RSA, ES256 for EC P-256).
   *
   * @param clientId   the OAuth2 {@code client_id} — used as both {@code iss} and {@code sub} claims
   * @param audience   the token endpoint URI — used as the {@code aud} claim
   * @param privateKey the private key used for signing
   */
  public JjwtClientAssertionProvider(String clientId, String audience, PrivateKey privateKey) {
    this.clientId = clientId;
    this.audience = audience;
    this.privateKey = privateKey;
  }

  /**
   * Generates and returns a freshly signed client assertion JWT.
   * JJWT automatically selects RS256 for RSAPrivateKey and ES256 for ECPrivateKey (P-256).
   *
   * @return serialized signed JWT string
   * @throws ExternalTaskClientException if signing fails
   */
  @Override
  public String getAssertion() {
    Date now = new Date();
    Date exp = new Date(now.getTime() + ASSERTION_LIFETIME_MS);

    try {
      return Jwts.builder()
          .issuer(clientId)
          .subject(clientId)
          .audience().add(audience).and()
          .id(UUID.randomUUID().toString())
          .issuedAt(now)
          .expiration(exp)
          .signWith(privateKey)
          .compact();
    } catch (JwtException e) {
      throw new ExternalTaskClientException("Failed to sign client assertion JWT", e);
    }
  }

}
