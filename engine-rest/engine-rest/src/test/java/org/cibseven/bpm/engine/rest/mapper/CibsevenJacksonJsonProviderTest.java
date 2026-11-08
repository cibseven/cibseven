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
package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Providers;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class CibsevenJacksonJsonProviderTest {

  @Test
  public void shouldUseObjectMapperContextResolverOfTheApplication() {
    JsonMapper customMapper = JsonMapper.builder().build();
    Providers providers = mock(Providers.class);
    ContextResolver<ObjectMapper> resolver = resolver(customMapper);
    when(providers.getContextResolver(eq(ObjectMapper.class), any())).thenReturn(resolver);

    assertSame(customMapper, provider(providers).locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE));
  }

  @Test
  public void shouldFallBackToJsonMapperContextResolver() {
    JsonMapper customMapper = JsonMapper.builder().build();
    Providers providers = mock(Providers.class);
    ContextResolver<JsonMapper> resolver = resolver(customMapper);
    when(providers.getContextResolver(eq(JsonMapper.class), any())).thenReturn(resolver);

    assertSame(customMapper, provider(providers).locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE));
  }

  @Test
  public void shouldFallBackToJacksonConfigurator() {
    assertSame(JacksonConfigurator.configureObjectMapper(),
        provider(mock(Providers.class)).locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE));
    assertSame(JacksonConfigurator.configureObjectMapper(),
        provider(null).locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE));
  }

  @Test
  public void shouldUseGivenMapper() {
    JsonMapper customMapper = JsonMapper.builder().build();
    assertSame(customMapper, new CibsevenJacksonJsonProvider(customMapper)
        .locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE));
  }

  protected CibsevenJacksonJsonProvider provider(Providers providers) {
    return new TestProvider(providers);
  }

  static class TestProvider extends CibsevenJacksonJsonProvider {
    TestProvider(Providers providers) {
      // normally injected by the JAX-RS runtime (@Context)
      _providers = providers;
    }
  }

  @SuppressWarnings("unchecked")
  protected <T> ContextResolver<T> resolver(T value) {
    ContextResolver<T> resolver = mock(ContextResolver.class);
    when(resolver.getContext(any())).thenReturn(value);
    return resolver;
  }

}
