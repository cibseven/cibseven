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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.cibseven.bpm.client.exception.ExternalTaskClientException;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link OAuth2ClientCredentialsProvider} reads the JSON of the token endpoint.
 */
public class OAuth2TokenResponseJsonTest {

  @Test
  public void shouldReadAccessTokenAndExpiry() {
    OAuth2ClientCredentialsProvider provider = provider(200,
        "{\"access_token\":\"abc.def\",\"token_type\":\"Bearer\",\"expires_in\":120,\"scope\":\"engine\",\"extra\":{\"a\":1}}");

    Instant before = Instant.now();
    assertThat(provider.getValidToken()).isEqualTo("abc.def");
    assertThat(provider.tokenExpiry).isBetween(before.plusSeconds(120), Instant.now().plusSeconds(120));
  }

  @Test
  public void shouldReadExpiryGivenAsString() {
    OAuth2ClientCredentialsProvider provider = provider(200, "{\"access_token\":\"abc\",\"expires_in\":\"90\"}");

    Instant before = Instant.now();
    assertThat(provider.getValidToken()).isEqualTo("abc");
    assertThat(provider.tokenExpiry).isBetween(before.plusSeconds(90), Instant.now().plusSeconds(90));
  }

  @Test
  public void shouldDefaultExpiryToOneHour() {
    OAuth2ClientCredentialsProvider provider = provider(200, "{\"access_token\":\"abc\"}");

    Instant before = Instant.now();
    assertThat(provider.getValidToken()).isEqualTo("abc");
    assertThat(provider.tokenExpiry).isBetween(before.plusSeconds(3600), Instant.now().plusSeconds(3600));
  }

  @Test
  public void shouldRejectMissingAccessToken() {
    assertTokenFailure(provider(200, "{\"token_type\":\"Bearer\"}"),
        "Token endpoint response did not contain a valid access_token");
  }

  @Test
  public void shouldRejectStructuredAccessToken() {
    assertTokenFailure(provider(200, "{\"access_token\":{\"value\":\"abc\"}}"),
        "Token endpoint response did not contain a valid access_token");
  }

  @Test
  public void shouldRejectResponseThatIsNoJson() {
    assertTokenFailure(provider(200, "<html>login</html>"), null);
  }

  @Test
  public void shouldReportErrorAndDescription() {
    assertTokenFailure(provider(401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}"),
        "Token endpoint returned HTTP 401: invalid_client - bad secret");
  }

  @Test
  public void shouldReportErrorWithoutDescription() {
    assertTokenFailure(provider(400, "{\"error\":\"invalid_scope\"}"), "Token endpoint returned HTTP 400: invalid_scope");
  }

  @Test
  public void shouldReportErrorThatIsNoJson() {
    assertTokenFailure(provider(502, "<html>Bad Gateway</html>"), "Token endpoint returned HTTP 502: unknown_error");
  }

  // helpers

  protected void assertTokenFailure(OAuth2ClientCredentialsProvider provider, String causeMessage) {
    Throwable thrown = catchThrowable(provider::getValidToken);

    assertThat(thrown).isInstanceOf(ExternalTaskClientException.class)
        .hasMessageContaining("Failed to acquire OAuth2 access token from token endpoint");
    if (causeMessage != null) {
      assertThat(thrown.getCause()).isInstanceOf(ExternalTaskClientException.class);
      assertThat(thrown.getCause().getMessage()).isEqualTo(causeMessage);
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  protected OAuth2ClientCredentialsProvider provider(int status, String body) {
    CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    try {
      when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
        HttpClientResponseHandler handler = invocation.getArgument(1);
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(status);
        response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return handler.handleResponse(response);
      });
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return OAuth2ClientCredentialsProvider.builder()
        .tokenUri("http://localhost/token")
        .clientId("client")
        .clientSecret("secret")
        .expiryBuffer(Duration.ZERO)
        .httpClient(httpClient)
        .build();
  }

}
