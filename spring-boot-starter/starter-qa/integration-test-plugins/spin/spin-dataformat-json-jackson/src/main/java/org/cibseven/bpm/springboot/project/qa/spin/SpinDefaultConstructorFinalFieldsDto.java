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
package org.cibseven.bpm.springboot.project.qa.spin;

import java.util.List;
import java.util.Objects;

/**
 * What Kotlin compiles for a data class whose properties all have default values: final fields, getters, a
 * constructor that takes all properties and checks them for null, and a constructor without parameters that
 * uses the defaults. Spin reads it through the constructor without parameters and sets the final fields, as
 * with Jackson 2, so properties missing in the JSON keep their default values.
 */
public final class SpinDefaultConstructorFinalFieldsDto {

  private final String name;
  private final int amount;
  private final List<String> tags;

  public SpinDefaultConstructorFinalFieldsDto() {
    this("default", 1, List.of());
  }

  public SpinDefaultConstructorFinalFieldsDto(String name, int amount, List<String> tags) {
    this.name = Objects.requireNonNull(name, "name");
    this.amount = amount;
    this.tags = Objects.requireNonNull(tags, "tags");
  }

  public String getName() {
    return name;
  }

  public int getAmount() {
    return amount;
  }

  public List<String> getTags() {
    return tags;
  }
}
