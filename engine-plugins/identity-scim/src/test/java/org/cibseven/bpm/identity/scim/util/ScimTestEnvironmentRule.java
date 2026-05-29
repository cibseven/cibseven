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

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit rule for managing SCIM test environment lifecycle.
 */
public class ScimTestEnvironmentRule implements TestRule {

  protected ScimTestEnvironment scimTestEnvironment;

  public ScimTestEnvironmentRule() {
    this.scimTestEnvironment = new ScimTestEnvironment();
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        scimTestEnvironment.init();
        try {
          base.evaluate();
        } finally {
          scimTestEnvironment.shutdown();
        }
      }
    };
  }

  public ScimTestEnvironment getScimTestEnvironment() {
    return scimTestEnvironment;
  }
}
