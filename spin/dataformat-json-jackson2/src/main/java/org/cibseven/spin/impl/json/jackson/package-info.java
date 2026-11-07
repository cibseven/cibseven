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
/**
 * Deprecated Jackson 2 variant of the Spin JSON data format.
 *
 * <p>Since CIB seven 3.0.0, {@code cibseven-spin-dataformat-json-jackson} and
 * {@code cibseven-spin-dataformat-all} are built on Jackson 3 ({@code tools.jackson}).
 * This artifact ({@code cibseven-spin-dataformat-json-jackson2}, shaded:
 * {@code cibseven-spin-dataformat-all-jackson2}) keeps the Jackson 2 implementation of CIB seven 2.x
 * with the same packages and class names as an escape hatch for applications that cannot move to
 * Jackson 3 yet. Never put both variants on one classpath.</p>
 *
 * <p><b>Deprecated:</b> will be removed in CIB seven 3.1.0.</p>
 *
 * @see Jackson2Variant
 */
package org.cibseven.spin.impl.json.jackson;
