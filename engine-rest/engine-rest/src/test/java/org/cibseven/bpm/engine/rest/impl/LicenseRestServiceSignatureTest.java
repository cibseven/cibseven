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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class LicenseRestServiceSignatureTest {

  private static final String LICENSE_KEY =
      "{\"customer\":\"Test Corp\",\"expires\":\"2026-12-31\",\"signature\":\"test_signature_value\"}";

  private LicenseRestServiceImpl service;
  private Method encryptSignature;
  private Method decryptSignature;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private String originalSecret;

  @Before
  public void setUp() throws Exception {
    service = new LicenseRestServiceImpl(null, new ObjectMapper());

    encryptSignature = LicenseRestServiceImpl.class.getDeclaredMethod("encryptSignature", String.class);
    encryptSignature.setAccessible(true);

    decryptSignature = LicenseRestServiceImpl.class.getDeclaredMethod("decryptSignature", String.class);
    decryptSignature.setAccessible(true);

    originalSecret = Configuration.getInstance().getSecret();
  }

  @After
  public void tearDown() throws Exception {
    setConfigurationSecret(originalSecret);
  }

  @Test
  public void testEncryptDecryptRoundTrip() throws Exception {
    String encrypted = (String) encryptSignature.invoke(service, LICENSE_KEY);

    // Encrypted result should differ from original
    assertNotEquals(LICENSE_KEY, encrypted);

    // The signature field should be replaced with a JWE token
    Map<String, Object> encryptedMap = objectMapper.readValue(encrypted, new TypeReference<Map<String, Object>>() {});
    assertNotEquals("test_signature_value", encryptedMap.get("signature"));

    // Decrypt should restore original values
    String decrypted = (String) decryptSignature.invoke(service, encrypted);
    Map<String, Object> originalMap = objectMapper.readValue(LICENSE_KEY, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> decryptedMap = objectMapper.readValue(decrypted, new TypeReference<Map<String, Object>>() {});
    assertEquals(originalMap, decryptedMap);
  }

  @Test
  public void testEncryptPreservesNonSignatureFields() throws Exception {
    String encrypted = (String) encryptSignature.invoke(service, LICENSE_KEY);

    Map<String, Object> encryptedMap = objectMapper.readValue(encrypted, new TypeReference<Map<String, Object>>() {});
    assertEquals("Test Corp", encryptedMap.get("customer"));
    assertEquals("2026-12-31", encryptedMap.get("expires"));
  }

  @Test(expected = InvalidRequestException.class)
  public void testEncryptWithMissingSecretThrows() throws Throwable {
    setConfigurationSecret(null);
    try {
      encryptSignature.invoke(service, LICENSE_KEY);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test(expected = InvalidRequestException.class)
  public void testDecryptWithCorruptedJweThrows() throws Throwable {
    String corrupted = "{\"customer\":\"Test Corp\",\"signature\":\"not-a-valid-jwe-token\"}";
    try {
      decryptSignature.invoke(service, corrupted);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test(expected = InvalidRequestException.class)
  public void testDecryptWithWrongKeyThrows() throws Throwable {
    // Encrypt with original secret
    String encrypted = (String) encryptSignature.invoke(service, LICENSE_KEY);

    // Change secret to simulate key rotation
    setConfigurationSecret("a-completely-different-secret-key-that-is-long-enough-for-testing-purposes");

    try {
      decryptSignature.invoke(service, encrypted);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private void setConfigurationSecret(String secret) throws Exception {
    Field secretField = Configuration.class.getDeclaredField("secret");
    secretField.setAccessible(true);
    secretField.set(Configuration.getInstance(), secret);
  }
}
