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
package org.cibseven.bpm.engine.impl.telemetry.dto;

import java.util.Map;

import org.cibseven.bpm.engine.telemetry.LicenseKeyData;

import com.google.gson.annotations.SerializedName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
public class LicenseKeyDataImpl implements LicenseKeyData {

  public static final String SERIALIZED_VALID_UNTIL = "valid-until";
  public static final String SERIALIZED_IS_UNLIMITED = "unlimited";

  protected String customer;
  protected String type;
  @SerializedName(value = SERIALIZED_VALID_UNTIL)
  @JsonProperty("expires")
  protected String validUntil;
  @SerializedName(value = SERIALIZED_IS_UNLIMITED)
  protected Boolean isUnlimited;
  protected Map<String, String> features;
  @JsonProperty("signature")
  protected String raw;

  public LicenseKeyDataImpl() {
  }

  public LicenseKeyDataImpl(String customer, String type, String validUntil, Boolean isUnlimited, Map<String, String> features, String raw) {
    this.customer = customer;
    this.type = type;
    this.validUntil = validUntil;
    this.isUnlimited = isUnlimited;
    this.features = features;
    this.raw = raw;
  }

  public static LicenseKeyDataImpl fromRawString(String rawLicense) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      LicenseKeyDataImpl resultLicense = objectMapper.readValue(rawLicense, LicenseKeyDataImpl.class);
      resultLicense.setRaw(null);
      return resultLicense;
    } catch (JsonProcessingException e) {
      // no message required?
      throw new RuntimeException(e.getMessage(), e);
    }
    //return new LicenseKeyDataImpl();
  }

  public boolean equals(LicenseKeyDataImpl other) {
    if (this == other) return true;
    if (other == null) return false;
    return java.util.Objects.equals(customer, other.customer)
        && java.util.Objects.equals(type, other.type)
        && java.util.Objects.equals(validUntil, other.validUntil)
        && java.util.Objects.equals(isUnlimited, other.isUnlimited)
        && java.util.Objects.equals(features, other.features)
        && java.util.Objects.equals(raw, other.raw);
  }
  
  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }


  public String getValidUntil() {
    return validUntil;
  }

  public void setValidUntil(String validUntil) {
    this.validUntil = validUntil;
  }

  public Boolean isUnlimited() {
    return isUnlimited;
  }

  public void setUnlimited(Boolean isUnlimited) {
    this.isUnlimited = isUnlimited;
  }

  public Map<String, String> getFeatures() {
    return features;
  }

  public void setFeatures(Map<String, String> features) {
    this.features = features;
  }

  public String getRaw() {
    return raw;
  }

  public void setRaw(String raw) {
    this.raw = raw;
  }

}