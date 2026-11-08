/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.rest.security.auth.impl.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CIB7-2493: a cibseven-webclient.properties without the secret was reported as a missing file.
 */
public class ConfigurationMissingSecretMessageTest {

  @Test
  public void namesTheMissingKeyWhenTheFileExists() {
    String message = Configuration.missingSecretMessage(true);

    assertThat(message)
        .doesNotContain("could not find configuration file")
        .contains("cibseven-webclient.properties in classpath has no cibseven.webclient.authentication.jwtSecret")
        .contains("CIBSEVEN_WEBCLIENT_AUTHENTICATION_JWTSECRET");
  }

  @Test
  public void namesTheMissingFileWhenThereIsNone() {
    String message = Configuration.missingSecretMessage(false);

    assertThat(message)
        .contains("could not find configuration file cibseven-webclient.properties in classpath")
        .contains("system property cibseven.webclient.authentication.jwtSecret");
  }
}
