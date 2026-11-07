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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

/**
 * Signs a client assertion with the JSON serializer jjwt finds on the classpath (jjwt-gson for the Jackson 3
 * based client, jjwt-jackson for the Jackson 2 variant) and verifies it.
 */
public class JjwtClientAssertionProviderTest {

  @Test
  public void shouldCreateSignedAssertion() throws Exception {
    // given
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    JjwtClientAssertionProvider provider = new JjwtClientAssertionProvider("my-client", "https://idp.example/token",
        keyPair.getPrivate());

    // when
    String assertion = provider.getAssertion();

    // then
    String[] parts = assertion.split("\\.");
    assertThat(parts).hasSize(3);
    String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
    assertThat(header).isEqualTo("{\"alg\":\"RS256\"}");

    Claims claims = Jwts.parser().verifyWith(keyPair.getPublic()).build().parseSignedClaims(assertion).getPayload();
    assertThat(claims.getIssuer()).isEqualTo("my-client");
    assertThat(claims.getSubject()).isEqualTo("my-client");
    assertThat(claims.getAudience()).containsExactly("https://idp.example/token");
    assertThat(claims.getId()).isNotBlank();
    assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(5 * 60 * 1000L);
  }

}
