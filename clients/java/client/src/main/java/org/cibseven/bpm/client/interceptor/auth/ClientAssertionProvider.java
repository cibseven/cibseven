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

/**
 * <p>Functional interface for supplying a signed client assertion JWT string.</p>
 *
 * <p>Implementations are responsible for creating or obtaining the assertion JWT,
 * which is sent as the {@code client_assertion} parameter in the OAuth2 client
 * credentials flow with JWT Bearer client authentication
 * (RFC 7523 / {@code urn:ietf:params:oauth:client-assertion-type:jwt-bearer}).</p>
 *
 * @see JjwtClientAssertionProvider
 * @see AzureWorkloadIdentityAssertionProvider
 */
@FunctionalInterface
public interface ClientAssertionProvider {

  /**
   * Returns a client assertion JWT string.
   * @return a serialized signed JWT assertion string; never {@code null}
   */
  String getAssertion();

}
