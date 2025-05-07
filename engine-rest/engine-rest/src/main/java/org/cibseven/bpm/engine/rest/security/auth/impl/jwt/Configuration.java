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
package org.cibseven.bpm.engine.rest.security.auth.impl.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Configuration {

  public static final String PROPERTIES_FILE = "cibseven-webclient.properties";

  private static Configuration instance;

  private String secret;

  public static Configuration getInstance() {
    if (instance == null) {
      instance = new Configuration();
    }
    return instance;
  }

  private Configuration() {
    Properties defaultSettings = loadProperties();
    this.secret = getProperty(defaultSettings, "cibseven.webclient.authentication.jwtSecret");
  }

  public String getSecret() {
    return secret;
  }

  private String getProperty(Properties defaultProperties, String propertyName) {
    return defaultProperties.getProperty(propertyName);
  }

  private Properties loadProperties() {
    Properties properties = new Properties();
    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
      if (inputStream == null) {
        throw new IllegalStateException("Could not find configuration file " + PROPERTIES_FILE + " in classpath");
      }
      properties.load(inputStream);
      return properties;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load template configuration from: " + PROPERTIES_FILE, e);
    }
  }

}
