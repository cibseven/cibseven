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
package org.cibseven.bpm.client.variable.json.fixture;

import java.util.Objects;

/**
 * Small nested bean of the JSON fixtures.
 */
public class FixtureItem {

  private String sku;
  private int qty;

  public FixtureItem() {
  }

  public FixtureItem(String sku, int qty) {
    this.sku = sku;
    this.qty = qty;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public int getQty() {
    return qty;
  }

  public void setQty(int qty) {
    this.qty = qty;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FixtureItem)) {
      return false;
    }
    FixtureItem other = (FixtureItem) o;
    return qty == other.qty && Objects.equals(sku, other.sku);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sku, qty);
  }

  @Override
  public String toString() {
    return "FixtureItem[" + sku + "," + qty + "]";
  }

}
