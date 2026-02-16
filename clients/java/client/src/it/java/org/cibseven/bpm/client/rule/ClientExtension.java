/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.client.rule;

import static org.cibseven.bpm.client.util.PropertyUtil.*;
import static org.cibseven.bpm.client.util.TestUtil.waitUntil;

import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.ExternalTaskClientBuilder;
import org.cibseven.bpm.client.util.PropertyUtil;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ClientExtension implements BeforeEachCallback, AfterEachCallback {

  public static final long LOCK_DURATION = 1000 * 60 * 5;

  protected ExternalTaskClientBuilder builder;
  protected ExternalTaskClient client;

  public ClientExtension() {
    this(PropertyUtil.loadProperties(DEFAULT_PROPERTIES_PATH));
  }

  public ClientExtension(Properties properties) {
    this(() -> {
      String endpoint = properties.getProperty(CAMUNDA_ENGINE_REST);
      String engine = properties.getProperty(CAMUNDA_ENGINE_NAME);
      return ExternalTaskClient.create()
          .baseUrl(endpoint + engine)
          .lockDuration(LOCK_DURATION);
    });
  }

  public ClientExtension(Supplier<ExternalTaskClientBuilder> builderSupplier) {
    this.builder = builderSupplier.get();
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    builder.disableAutoFetching();
    client = builder.build();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    client.stop();
    client = null;
  }

  public ExternalTaskClient client() {
    return client;
  }

  public void waitForFetchAndLockUntil(BooleanSupplier condition) {
    client.start();

    try {
      waitUntil(condition);
    }
    finally {
      client.stop();
    }

  }
}
