/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.rest.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.rest.LicenseRestService;
import org.cibseven.bpm.engine.rest.dto.license.LicenseKeyDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;

public class LicenseRestServiceImpl extends AbstractRestProcessEngineAware implements LicenseRestService {

  public LicenseRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

  @Override
  public void setLicenseKey(LicenseKeyDto licenseKey) {
    licenseKey.setLicenseKey(decryptSignature(licenseKey.getLicenseKey()));
    getProcessEngine().getManagementService().setLicenseKey(licenseKey.getLicenseKey());
  }

  @Override
  public String getLicenseKey() {
    String licenseKey = getProcessEngine().getManagementService().getLicenseKey();
    if (licenseKey != null) 
      licenseKey = encryptSignature(licenseKey);
    return licenseKey;
  }
  
  private SecretKeySpec getAesKey(String secret) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    } catch (NoSuchAlgorithmException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e, "SHA-256 not available");
    }
  }

  private String encryptSignature(String licenseKey) {
  ObjectMapper objectMapper = new ObjectMapper();
  try {
    Map<String, Object> map = objectMapper.readValue(licenseKey, new TypeReference<Map<String, Object>>() {
    });
    String signature = (String) map.get("signature");
    String secret = Configuration.getInstance().getSecret();
    SecretKeySpec key = getAesKey(secret);
    String jwe = Jwts.builder()
      .content(signature)
      .encryptWith(key, Jwts.ENC.A256GCM)
      .compact();
    map.put("signature", jwe);
    return objectMapper.writeValueAsString(map);
  } catch (JsonProcessingException e) {
    throw new InvalidRequestException(Status.BAD_REQUEST, e, e.getMessage());
  }
  }
  
  private String decryptSignature(String licenseKey) {
      ObjectMapper objectMapper = new ObjectMapper();
      try {
          Map<String, Object> map = objectMapper.readValue(licenseKey, new TypeReference<Map<String, Object>>() {
          });
          String jwe = (String) map.get("signature");
          String secret = Configuration.getInstance().getSecret();
          SecretKeySpec key = getAesKey(secret);
          byte[] payload = Jwts.parser()
              .decryptWith(key)
              .build()
              .parseEncryptedContent(jwe)
              .getPayload();
          String signature = new String(payload, StandardCharsets.UTF_8);
          map.put("signature", signature);
          return objectMapper.writeValueAsString(map);
      } catch (JsonProcessingException e) {
        throw new InvalidRequestException(Status.BAD_REQUEST, e, e.getMessage());
      }
  }

}