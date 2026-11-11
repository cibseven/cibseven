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
package org.cibseven.bpm.identity.scim.util;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit rule for managing SCIM test environment lifecycle.
 */
public class ScimTestEnvironmentRule implements BeforeAllCallback, AfterAllCallback {

  protected ScimTestEnvironment scimTestEnvironment;

  public ScimTestEnvironmentRule() {
    this.scimTestEnvironment = new ScimTestEnvironment();
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    scimTestEnvironment.init();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    scimTestEnvironment.shutdown();
  }

  public ScimTestEnvironment getScimTestEnvironment() {
    return scimTestEnvironment;
  }
}
