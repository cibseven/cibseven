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
package org.cibseven.spin.impl.json.jackson;

/**
 * Marker class of the deprecated Jackson 2 variant of the Spin JSON data format
 * ({@code cibseven-spin-dataformat-json-jackson2}, also shaded into
 * {@code cibseven-spin-dataformat-all-jackson2}).
 *
 * <p>The Jackson 3 variant ({@code cibseven-spin-dataformat-json-jackson}) has the same packages and
 * class names but not this class, so integrations can tell the two apart on the classpath, for
 * example to register Jackson 2 modules only for the Jackson 2 variant.</p>
 *
 * @deprecated the Jackson 2 variant of Spin is removed in CIB seven 3.1.0; move to
 *   {@code cibseven-spin-dataformat-json-jackson} (Jackson 3).
 */
@Deprecated(since = "3.0.0", forRemoval = true)
public final class Jackson2Variant {

  /** Fully qualified name of this class, for class presence checks by name. */
  public static final String CLASS_NAME = "org.cibseven.spin.impl.json.jackson.Jackson2Variant";

  private Jackson2Variant() {
  }

}
