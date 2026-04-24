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
package org.cibseven.bpm.engine.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cibseven.bpm.engine.rest.exception.InvalidRequestException;
import org.cibseven.bpm.engine.rest.impl.LicenseRestServiceImpl;
import org.cibseven.bpm.engine.rest.security.auth.impl.jwt.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the private {@code encryptSignature} and {@code decryptSignature}
 * methods in {@link LicenseRestServiceImpl}.
 *
 * <p>The methods under test perform AES-256-GCM authenticated encryption of the
 * {@code signature} field inside a license-key JSON document. Reflection is used
 * to access the private methods without requiring a full REST deployment.
 */
public class LicenseRestServiceTest {

    private static final String SAMPLE_LICENSE_JSON =
            "{\"customer\":\"ACME Corp\"," +
            "\"validUntil\":\"2027-12-31\"," +
            "\"signature\":\"abc123signatureValue\"}";

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
        setConfigurationSecret("my-super-secret-jwt-key-for-testing");
    }

    @After
    public void tearDown() throws Exception {
        setConfigurationSecret(originalSecret);
    }

    // -----------------------------------------------------------------------
    // Round-trip tests
    // -----------------------------------------------------------------------

    @Test
    public void encryptThenDecrypt_restoresOriginalSignature() throws Exception {
        String encrypted = invoke(encryptSignature, SAMPLE_LICENSE_JSON);
        String decrypted = invoke(decryptSignature, encrypted);

        Map<String, Object> original  = objectMapper.readValue(SAMPLE_LICENSE_JSON, new TypeReference<>() {});
        Map<String, Object> recovered = objectMapper.readValue(decrypted,           new TypeReference<>() {});

        assertEquals("Signature value must be identical after encrypt→decrypt round-trip",
                original.get("signature"), recovered.get("signature"));
    }

    @Test
    public void encryptThenDecrypt_preservesOtherFields() throws Exception {
        String encrypted = invoke(encryptSignature, SAMPLE_LICENSE_JSON);
        String decrypted = invoke(decryptSignature, encrypted);

        Map<String, Object> original  = objectMapper.readValue(SAMPLE_LICENSE_JSON, new TypeReference<>() {});
        Map<String, Object> recovered = objectMapper.readValue(decrypted,           new TypeReference<>() {});

        assertEquals("customer field must be preserved",
                original.get("customer"),   recovered.get("customer"));
        assertEquals("validUntil field must be preserved",
                original.get("validUntil"), recovered.get("validUntil"));
    }

    @Test
    public void encrypt_producesBase64EncodedCiphertext() throws Exception {
        String encrypted = invoke(encryptSignature, SAMPLE_LICENSE_JSON);

        Map<String, Object> map = objectMapper.readValue(encrypted, new TypeReference<Map<String, Object>>() {});
        String encSig = (String) map.get("signature");

        byte[] decoded = java.util.Base64.getDecoder().decode(encSig);
        assertTrue("Encoded blob must contain IV (12 bytes) + ciphertext + GCM tag (16 bytes)",
                decoded.length > 12 + 16);
        assertNotEquals("Signature must not be stored in plaintext after encryption",
                "abc123signatureValue", encSig);
    }

    @Test
    public void encryptCalledTwice_producesDifferentCiphertext() throws Exception {
        String enc1 = invoke(encryptSignature, SAMPLE_LICENSE_JSON);
        String enc2 = invoke(encryptSignature, SAMPLE_LICENSE_JSON);

        String sig1 = (String) objectMapper.readValue(enc1, new TypeReference<Map<String, Object>>() {}).get("signature");
        String sig2 = (String) objectMapper.readValue(enc2, new TypeReference<Map<String, Object>>() {}).get("signature");

        assertNotEquals("Each encryption call must produce a unique ciphertext due to random IV", sig1, sig2);
    }

    @Test
    public void encrypt_withEmptySignature_roundTripsCorrectly() throws Exception {
        String input = "{\"customer\":\"Test\",\"signature\":\"\"}";

        String encrypted = invoke(encryptSignature, input);
        String decrypted = invoke(decryptSignature, encrypted);

        Map<String, Object> recovered = objectMapper.readValue(decrypted, new TypeReference<Map<String, Object>>() {});
        assertEquals("An empty signature must survive the encrypt→decrypt round-trip unchanged",
                "", recovered.get("signature"));
    }

    // -----------------------------------------------------------------------
    // Error / security tests
    // -----------------------------------------------------------------------

    @Test
    public void decrypt_withWrongSecret_throwsInvalidRequestException() throws Exception {
        String encrypted = invoke(encryptSignature, SAMPLE_LICENSE_JSON);
        setConfigurationSecret("a-completely-different-secret-key-that-is-long-enough");

        try {
            decryptSignature.invoke(service, encrypted);
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("Decrypting with the wrong AES key must throw InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    @Test
    public void decrypt_withTamperedCiphertext_throwsInvalidRequestException() throws Exception {
        String encrypted = invoke(encryptSignature, SAMPLE_LICENSE_JSON);

        Map<String, Object> map = objectMapper.readValue(encrypted, new TypeReference<Map<String, Object>>() {});
        String original = (String) map.get("signature");
        char last = original.charAt(original.length() - 1);
        String tampered = original.substring(0, original.length() - 1) + (last == 'A' ? 'B' : 'A');
        map.put("signature", tampered);
        String tamperedJson = objectMapper.writeValueAsString(map);

        try {
            decryptSignature.invoke(service, tamperedJson);
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("GCM authentication tag failure on tampered data must surface as InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    @Test
    public void encrypt_withMissingSecret_throwsInvalidRequestException() throws Exception {
        setConfigurationSecret(null);

        try {
            encryptSignature.invoke(service, SAMPLE_LICENSE_JSON);
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("A null JWT secret must throw InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    @Test
    public void encrypt_withEmptySecret_throwsInvalidRequestException() throws Exception {
        setConfigurationSecret("");

        try {
            encryptSignature.invoke(service, SAMPLE_LICENSE_JSON);
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("A blank JWT secret must throw InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    @Test
    public void encrypt_withInvalidJson_throwsInvalidRequestException() throws Exception {
        try {
            encryptSignature.invoke(service, "not-valid-json");
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("Non-JSON input to encryptSignature must throw InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    @Test
    public void decrypt_withInvalidJson_throwsInvalidRequestException() throws Exception {
        try {
            decryptSignature.invoke(service, "not-valid-json");
            fail("Expected InvalidRequestException");
        } catch (InvocationTargetException e) {
            assertTrue("Non-JSON input to decryptSignature must throw InvalidRequestException",
                    e.getCause() instanceof InvalidRequestException);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Invokes a reflected method and unwraps InvocationTargetException so tests read cleanly. */
    @SuppressWarnings("unchecked")
    private <T> T invoke(Method method, String arg) throws Exception {
        try {
            return (T) method.invoke(service, arg);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private void setConfigurationSecret(String secret) throws Exception {
        Field secretField = Configuration.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        secretField.set(Configuration.getInstance(), secret);
    }
}