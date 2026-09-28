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

import org.cibseven.bpm.client.exception.ExternalTaskClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * <p>Supplies a federated identity token for Azure Workload Identity authentication.</p>
 *
 * <p>Azure Workload Identity injects a file containing a short-lived service account token
 * (signed by the Kubernetes OIDC issuer) into the workload pod. This token can be exchanged
 * for an Azure Entra ID access token via the
 * {@code urn:ietf:params:oauth:client-assertion-type:jwt-bearer} mechanism.</p>
 *
 * <p>The file path is read from the {@code AZURE_FEDERATED_TOKEN_FILE} environment variable,
 * which is injected automatically by the Azure Workload Identity mutating webhook. The file
 * is re-read on every {@link #getAssertion()} call because Azure rotates the token
 * periodically.</p>
 *
 * <p>Required environment variable:</p>
 * <ul>
 *   <li>{@code AZURE_FEDERATED_TOKEN_FILE} — absolute path to the projected token file
 *       (e.g. {@code /var/run/secrets/azure/tokens/azure-identity-token})</li>
 * </ul>
 */
public class AzureWorkloadIdentityAssertionProvider implements ClientAssertionProvider {

  protected static final String ENV_FEDERATED_TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

  /**
   * Reads and returns the federated identity token from the file path referenced
   * by the {@code AZURE_FEDERATED_TOKEN_FILE} environment variable.
   *
   * <p>The file is read fresh on every call because Azure Workload Identity rotates
   * the token file automatically.</p>
   *
   * @return the raw federated token string (used directly as {@code client_assertion})
   * @throws ExternalTaskClientException if the environment variable is not set or the file cannot be read
   */
  @Override
  public String getAssertion() {
    String tokenFilePath = System.getenv(ENV_FEDERATED_TOKEN_FILE);
    if (tokenFilePath == null || tokenFilePath.isBlank()) {
      throw new ExternalTaskClientException(
          "Environment variable '" + ENV_FEDERATED_TOKEN_FILE + "' is not set. "
              + "Ensure the Azure Workload Identity webhook has been applied to this workload.");
    }

    try {
      return Files.readString(Paths.get(tokenFilePath), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new ExternalTaskClientException(
          "Failed to read Azure federated identity token from file '" + tokenFilePath + "'", e);
    }
  }

}
