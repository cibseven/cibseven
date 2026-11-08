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
package org.cibseven.bpm.client.impl;

import java.net.URL;

/**
 * The external task client exists in two variants with the same packages and classes:
 * {@code cibseven-external-task-client} (Jackson 3) and the deprecated {@code cibseven-external-task-client-jackson2}
 * (Jackson 2, removed in 3.1.0). With both on one class path it is undefined which classes are used, so the client
 * refuses to start. Each variant contains its own marker resource.
 */
public final class JacksonVariantCheck {

  public static final String JACKSON3_MARKER = "META-INF/cibseven/external-task-client-jackson3.properties";
  public static final String JACKSON2_MARKER = "META-INF/cibseven/external-task-client-jackson2.properties";

  private static final ExternalTaskClientLogger LOG = ExternalTaskClientLogger.CLIENT_LOGGER;

  private JacksonVariantCheck() {
  }

  /**
   * @throws org.cibseven.bpm.client.exception.ExternalTaskClientException if both variants are visible to the class
   *     loader
   */
  public static void ensureSingleVariant(ClassLoader classLoader) {
    if (classLoader == null) {
      return;
    }
    URL jackson3 = classLoader.getResource(JACKSON3_MARKER);
    URL jackson2 = classLoader.getResource(JACKSON2_MARKER);
    if (jackson3 != null && jackson2 != null) {
      throw LOG.bothJacksonVariantsOnClasspathException(jackson3, jackson2);
    }
  }

}
