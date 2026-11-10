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
package org.cibseven.bpm.engine.impl.telemetry.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The license key is read with the engine's JSON nesting limit (CIB7-2634): Gson 2.12+ alone stops at 255 levels.
 */
public class LicenseKeyDataImplTest {

  @Test
  public void shouldReadLicenseKey() {
    LicenseKeyDataImpl license = LicenseKeyDataImpl.fromRawString(
        "{\"customer\":\"CIB\",\"type\":\"enterprise\",\"expires\":\"2027-12-31\",\"unlimited\":false,"
        + "\"features\":{\"camundaBPM\":\"true\"}}");

    assertThat(license.getCustomer()).isEqualTo("CIB");
    assertThat(license.getType()).isEqualTo("enterprise");
    assertThat(license.getValidUntil()).isEqualTo("2027-12-31");
    assertThat(license.getFeatures()).containsEntry("camundaBPM", "true");
    assertThat(license.getRaw()).isNull();
  }

  @Test
  public void shouldReadLicenseKeyWithDeeplyNestedUnknownProperty() {
    // 300 levels: over Gson's default limit of 255, within the engine's limit of 1000
    String nested = "[".repeat(300) + "]".repeat(300);

    LicenseKeyDataImpl license = LicenseKeyDataImpl.fromRawString("{\"customer\":\"CIB\",\"extra\":" + nested + "}");

    assertThat(license).isNotNull();
    assertThat(license.getCustomer()).isEqualTo("CIB");
  }
}
