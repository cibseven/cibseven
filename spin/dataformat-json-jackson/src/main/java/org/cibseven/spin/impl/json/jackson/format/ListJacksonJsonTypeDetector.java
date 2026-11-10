/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.spin.impl.json.jackson.format;

import tools.jackson.databind.JavaType;

import java.lang.reflect.TypeVariable;
import java.util.List;

public class ListJacksonJsonTypeDetector extends AbstractJacksonJsonTypeDetector {

  public boolean canHandle(Object object) {
    return object instanceof List;
  }

  public String detectType(Object object) {
    return constructType(object).toCanonical();
  }

  /**
   * The element type is taken from the first element that is not {@code null}; a list of {@code null}s gets the
   * element type {@code Object}, like an empty list.
   */
  protected JavaType constructType(Object object) {

    if (object == null) {
      return getTypeFactory().constructType(Object.class);
    }

    if (object instanceof List && !((List<?>) object).isEmpty()) {
      List<?> list = (List<?>) object;
      if (bindingsArePresent(list.getClass())) {
        final JavaType elementType = constructType(firstNonNullElement(list));
        return getTypeFactory().constructCollectionType(list.getClass(), elementType);
      }
    }
    return getTypeFactory().constructType(object.getClass());
  }

  protected Object firstNonNullElement(List<?> list) {
    for (Object element : list) {
      if (element != null) {
        return element;
      }
    }
    return null;
  }

  private boolean bindingsArePresent(Class<?> erasedType) {
    TypeVariable<?>[] vars = erasedType.getTypeParameters();
    int varLen = (vars == null) ? 0 : vars.length;
    if (varLen == 0) {
      return false;
    }
    if (varLen != 1) {
      throw new IllegalArgumentException("Cannot create TypeBindings for class " + erasedType.getName() + " with 1 type parameter: class expects " + varLen);
    }
    return true;
  }

}
