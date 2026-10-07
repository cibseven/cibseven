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
package org.cibseven.bpm.application.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
public class EjbProcessApplicationTest {

  @Test
  public void shouldBeDetectedAsJakartaEjbProcessApplication() {
    // container integrations that only check for the Jakarta class must still
    // recognize applications built against the deprecated pre-Jakarta name
    assertThat(new CustomEjbProcessApplication()).isInstanceOf(JakartaEjbProcessApplication.class);
  }

  public static class CustomEjbProcessApplication extends EjbProcessApplication {
  }

}
