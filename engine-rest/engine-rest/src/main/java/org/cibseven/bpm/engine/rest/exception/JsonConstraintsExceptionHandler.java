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
package org.cibseven.bpm.engine.rest.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import tools.jackson.core.exc.StreamConstraintsException;

/**
 * A request exceeds a limit of the JSON reader (nesting depth, string length, number length).
 * <p>
 * Inside a property of a request body, the response is 400 like with Jackson 2, which wrapped this error into a
 * {@code JsonMappingException}. Jackson 3 keeps the exception type and only adds the property path, so neither
 * {@link JsonMappingExceptionHandler} nor {@link JsonParseExceptionHandler} applies. Without a path (the top level
 * of a document, or writing a response), the response stays the one of {@link ExceptionHandler}, as with Jackson 2.
 */
@Provider
public class JsonConstraintsExceptionHandler implements ExceptionMapper<StreamConstraintsException> {

  @Override
  public Response toResponse(StreamConstraintsException exception) {
    if (exception.getPath().isEmpty()) {
      return ExceptionHandlerHelper.getInstance().getResponse(exception);
    }
    InvalidRequestException badRequestException = new InvalidRequestException(Status.BAD_REQUEST, exception, "");
    return ExceptionHandlerHelper.getInstance().getResponse(badRequestException);
  }
}
