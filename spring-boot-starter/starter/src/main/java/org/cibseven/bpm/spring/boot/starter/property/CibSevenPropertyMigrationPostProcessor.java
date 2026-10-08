/*
 * Copyright CIB seven GmbH and/or licensed to CIB seven GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB seven licenses this file to you under the Apache License,
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
package org.cibseven.bpm.spring.boot.starter.property;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * An {@link EnvironmentPostProcessor} that translates properties from the
 * {@code cibseven.bpm.*} namespace to the canonical {@code camunda.bpm.*}
 * namespace used by {@link CamundaBpmProperties}.
 * <p>
 * This allows users to configure the engine using the new {@code cibseven.bpm}
 * prefix while maintaining full backward compatibility with the existing
 * {@code camunda.bpm} prefix. If the same property is defined under both
 * namespaces, the {@code cibseven.bpm} value takes precedence.
 */
public class CibSevenPropertyMigrationPostProcessor implements EnvironmentPostProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(CibSevenPropertyMigrationPostProcessor.class);

  static final String NEW_PREFIX = "cibseven.bpm.";
  static final String LEGACY_PREFIX = "camunda.bpm.";
  static final String PROPERTY_SOURCE_NAME = "cibsevenBpmTranslated";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    Map<String, Object> translatedProperties = new HashMap<>();
    MutablePropertySources propertySources = environment.getPropertySources();

    for (PropertySource<?> propertySource : propertySources) {
      if (propertySource instanceof EnumerablePropertySource) {
        EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) propertySource;
        for (String name : enumerable.getPropertyNames()) {
          if (name.startsWith(NEW_PREFIX)) {
            String legacyName = LEGACY_PREFIX + name.substring(NEW_PREFIX.length());
            Object value = enumerable.getProperty(name);
            if (value != null) {
              translatedProperties.put(legacyName, value);
            }
          }
        }
      }
    }

    if (!translatedProperties.isEmpty()) {
      LOG.info("Translating {} propert{} from '{}' namespace to '{}' namespace",
          translatedProperties.size(),
          translatedProperties.size() == 1 ? "y" : "ies",
          NEW_PREFIX.substring(0, NEW_PREFIX.length() - 1),
          LEGACY_PREFIX.substring(0, LEGACY_PREFIX.length() - 1));
      // Add with highest priority so cibseven.bpm.* values win over camunda.bpm.*
      propertySources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, translatedProperties));
    }
  }

}
