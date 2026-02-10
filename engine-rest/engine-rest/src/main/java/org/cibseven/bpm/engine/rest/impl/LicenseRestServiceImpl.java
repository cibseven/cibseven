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

import java.util.Map;

import javax.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.impl.telemetry.dto.LicenseKeyDataImpl;
import org.cibseven.bpm.engine.rest.LicenseRestService;
import org.cibseven.bpm.engine.rest.dto.license.LicenseKeyDto;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

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
    licenseKey = encryptSignature(licenseKey);
    return licenseKey;
  }
  
  private String encryptSignature(String licenseKey) {
    try {
      Map<String, Object> map = objectMapper.readValue(licenseKey, new TypeReference<Map<String,Object>>(){});
      String signature = (String)map.get("signature");
      String jwtSecret = Configuration.getInstance().getSecret();
      String token = Jwts.builder()
          .claim("licenseSignature", signature)
          .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()), Jwts.SIG.HS256)
          .compact();
      map.put("signature", token);
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  private String decryptSignature(String licenseKey) {
    try {
      Map<String, Object> map = objectMapper.readValue(licenseKey, new TypeReference<Map<String,Object>>(){});
      String signature = (String)map.get("signature");
      String jwtSecret = Configuration.getInstance().getSecret();
      String decodedSignature = Jwts.parser()
          .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
          .build()
          .parseSignedClaims(signature)
          .getPayload()
          .get("licenseSignature", String.class);
      map.put("signature", decodedSignature);
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new InvalidRequestException(Status.BAD_REQUEST, e.getMessage());
    }
  }

}