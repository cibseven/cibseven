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

import java.util.Arrays;
import java.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public class LicenseRestServiceImpl extends AbstractRestProcessEngineAware implements LicenseRestService {

       private static final int GCM_IV_LENGTH = 12;
       private static final int GCM_TAG_LENGTH_BITS = 128;
       private static final SecureRandom RNG = new SecureRandom();

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
  
  private String getSecret() {
    String secret = Configuration.getInstance().getSecret();
    if (secret == null || secret.isEmpty()) {
      throw new InvalidRequestException(Status.FORBIDDEN,
          "JWT secret is not configured. Cannot encrypt/decrypt license signature.");
    }
    return secret;
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
      String secret = getSecret();
      SecretKeySpec key = getAesKey(secret);
      byte[] iv = new byte[GCM_IV_LENGTH];
      RNG.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      byte[] ct = cipher.doFinal(signature.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      map.put("signature", Base64.getEncoder().encodeToString(out));
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e, "Invalid license key JSON format");
    } catch (Exception e) {
      throw new InvalidRequestException(Status.INTERNAL_SERVER_ERROR, e,
          "Failed to encrypt license signature");
    }
  }

  private String decryptSignature(String licenseKey) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      Map<String, Object> map = objectMapper.readValue(licenseKey, new TypeReference<Map<String, Object>>() {
      });
      String encoded = (String) map.get("signature");
      SecretKeySpec key = getAesKey(getSecret());
      byte[] raw = Base64.getDecoder().decode(encoded);
      byte[] iv = Arrays.copyOfRange(raw, 0, GCM_IV_LENGTH);
      byte[] ct = Arrays.copyOfRange(raw, GCM_IV_LENGTH, raw.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      String signature = new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
      map.put("signature", signature);
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e, "Invalid license key JSON format");
    } catch (Exception e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e, "Failed to decrypt license signature. The JWT secret may have been rotated.");
    }
  }

}