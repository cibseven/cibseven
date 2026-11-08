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
package org.cibseven.spin.json.golden;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Fields are declared in non-alphabetical order on purpose (Jackson 3 sorts alphabetically by default).
public class Customer {
  private String name;
  private int age;
  private Date createdAt;
  private Status status;
  private List<Item> items;
  private Map<String, Object> attributes;
  private BigDecimal balance;
  private boolean active;

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public int getAge() { return age; }
  public void setAge(int age) { this.age = age; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Status getStatus() { return status; }
  public void setStatus(Status status) { this.status = status; }
  public List<Item> getItems() { return items; }
  public void setItems(List<Item> items) { this.items = items; }
  public Map<String, Object> getAttributes() { return attributes; }
  public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
  public BigDecimal getBalance() { return balance; }
  public void setBalance(BigDecimal balance) { this.balance = balance; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }

  @Override
  public boolean equals(Object o) {
    return o instanceof Customer c && age == c.age && active == c.active && Objects.equals(name, c.name)
        && Objects.equals(createdAt, c.createdAt) && status == c.status && Objects.equals(items, c.items)
        && Objects.equals(attributes, c.attributes)
        && (balance == null ? c.balance == null : c.balance != null && balance.compareTo(c.balance) == 0);
  }

  @Override
  public int hashCode() { return Objects.hash(name, age, createdAt, status, items, active); }

  @Override
  public String toString() {
    return "Customer[" + name + "," + age + "," + createdAt + "," + status + "," + items + "," + attributes + ","
        + balance + "," + active + "]";
  }
}
