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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.jakarta.rs.json.JacksonJsonProvider;

/**
 * The JAX-RS JSON provider of the REST API (Jackson 3).
 *
 * <p>Like the Jackson 2 provider before, it uses the mapper of the
 * <code>ContextResolver&lt;ObjectMapper&gt;</code> of the application, by default
 * {@link JacksonConfigurator}. The Jackson 3 {@link JacksonJsonProvider} only looks up a
 * <code>ContextResolver&lt;JsonMapper&gt;</code> and would otherwise fall back to a mapper
 * with Jackson 3 defaults (dates as timestamps, unknown properties fail, ...).
 *
 * <p>Without a resolver (or if it returns no {@link JsonMapper}), the provider uses
 * {@link JacksonConfigurator#configureObjectMapper()}.
 */
@Provider
@Consumes(MediaType.WILDCARD)
@Produces({MediaType.APPLICATION_JSON, "text/json", MediaType.WILDCARD})
public class CibsevenJacksonJsonProvider extends JacksonJsonProvider {

  /**
   * Looks the mapper up in the JAX-RS application, see the class comment.
   */
  public CibsevenJacksonJsonProvider() {
    super();
  }

  /**
   * Always uses the given mapper.
   */
  public CibsevenJacksonJsonProvider(JsonMapper mapper) {
    super(mapper);
  }

  @Override
  protected JsonMapper _locateMapperViaProvider(Class<?> type, MediaType mediaType) {
    if (_providers != null) {
      ContextResolver<ObjectMapper> resolver = _providers.getContextResolver(ObjectMapper.class, mediaType);
      if (resolver == null) {
        resolver = _providers.getContextResolver(ObjectMapper.class, null);
      }
      if (resolver != null) {
        ObjectMapper mapper = resolver.getContext(type);
        if (mapper instanceof JsonMapper) {
          return (JsonMapper) mapper;
        }
      }

      ContextResolver<JsonMapper> jsonMapperResolver = _providers.getContextResolver(JsonMapper.class, mediaType);
      if (jsonMapperResolver == null) {
        jsonMapperResolver = _providers.getContextResolver(JsonMapper.class, null);
      }
      if (jsonMapperResolver != null) {
        JsonMapper mapper = jsonMapperResolver.getContext(type);
        if (mapper != null) {
          return mapper;
        }
      }
    }
    return JacksonConfigurator.configureObjectMapper();
  }

}
