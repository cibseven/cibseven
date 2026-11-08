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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A typical user bean as it is stored as a JSON object variable: scalars, wrapper types, big numbers, a
 * {@link Date}, an enum, collections, a nested bean, binary data, a renamed and an ignored property.
 * The field order is part of the fixture (Jackson writes the properties in declaration order).
 */
public class FixtureBean {

  private String name;
  private int count;
  private long bigCount;
  private boolean active;
  private Boolean flag;
  private double ratio;
  private float factor;
  private BigDecimal amount;
  private BigInteger huge;
  private Date createdAt;
  private FixtureStatus status;
  private List<String> tags;
  private Map<String, Object> attributes;
  private FixtureItem mainItem;
  private List<FixtureItem> items;
  private byte[] payload;
  private char initial;
  private String nothing;

  @JsonProperty("display_name")
  private String displayName;

  @JsonIgnore
  private String secret;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public int getCount() {
    return count;
  }

  public void setCount(int count) {
    this.count = count;
  }

  public long getBigCount() {
    return bigCount;
  }

  public void setBigCount(long bigCount) {
    this.bigCount = bigCount;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Boolean getFlag() {
    return flag;
  }

  public void setFlag(Boolean flag) {
    this.flag = flag;
  }

  public double getRatio() {
    return ratio;
  }

  public void setRatio(double ratio) {
    this.ratio = ratio;
  }

  public float getFactor() {
    return factor;
  }

  public void setFactor(float factor) {
    this.factor = factor;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public BigInteger getHuge() {
    return huge;
  }

  public void setHuge(BigInteger huge) {
    this.huge = huge;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public FixtureStatus getStatus() {
    return status;
  }

  public void setStatus(FixtureStatus status) {
    this.status = status;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(List<String> tags) {
    this.tags = tags;
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public void setAttributes(Map<String, Object> attributes) {
    this.attributes = attributes;
  }

  public FixtureItem getMainItem() {
    return mainItem;
  }

  public void setMainItem(FixtureItem mainItem) {
    this.mainItem = mainItem;
  }

  public List<FixtureItem> getItems() {
    return items;
  }

  public void setItems(List<FixtureItem> items) {
    this.items = items;
  }

  public byte[] getPayload() {
    return payload;
  }

  public void setPayload(byte[] payload) {
    this.payload = payload;
  }

  public char getInitial() {
    return initial;
  }

  public void setInitial(char initial) {
    this.initial = initial;
  }

  public String getNothing() {
    return nothing;
  }

  public void setNothing(String nothing) {
    this.nothing = nothing;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret(String secret) {
    this.secret = secret;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FixtureBean)) {
      return false;
    }
    FixtureBean other = (FixtureBean) o;
    // secret is @JsonIgnore'd and therefore not part of the comparison
    return count == other.count
        && bigCount == other.bigCount
        && active == other.active
        && Double.compare(ratio, other.ratio) == 0
        && Float.compare(factor, other.factor) == 0
        && initial == other.initial
        && Objects.equals(name, other.name)
        && Objects.equals(flag, other.flag)
        && Objects.equals(amount, other.amount)
        && Objects.equals(huge, other.huge)
        && Objects.equals(createdAt, other.createdAt)
        && status == other.status
        && Objects.equals(tags, other.tags)
        && Objects.equals(attributes, other.attributes)
        && Objects.equals(mainItem, other.mainItem)
        && Objects.equals(items, other.items)
        && Arrays.equals(payload, other.payload)
        && Objects.equals(nothing, other.nothing)
        && Objects.equals(displayName, other.displayName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, count, bigCount, status);
  }

  @Override
  public String toString() {
    return "FixtureBean[" + name + "," + count + "," + status + "," + items + "]";
  }

}
