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
 * A JSON limit (nesting depth, string length, number length) is exceeded while reading a request or writing a
 * response.
 * <p>
 * When the exception carries a property path, the response is 400 like with Jackson 2, which wrapped this error
 * into a {@code JsonMappingException}. Jackson 3 keeps the exception type and only adds the path, so neither
 * {@link JsonMappingExceptionHandler} nor {@link JsonParseExceptionHandler} applies. This holds for writing too: a
 * response value nested too deep inside a property gives 400, as before.
 * <p>
 * Without a path, the response stays the one of {@link ExceptionHandler}, as with Jackson 2. Examples are a number
 * that is too long, or nesting inside an unknown property that is skipped. A path does not tell a request from a
 * response. A response that is already committed cannot be replaced.
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
