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
package org.cibseven.bpm.engine.test.api.form;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormData;
import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.TaskFormData;
import org.cibseven.bpm.engine.impl.form.engine.AbstractRenderFormDelegate;
import org.cibseven.bpm.engine.impl.form.type.EnumFormType;
import org.cibseven.bpm.engine.impl.form.type.StringFormType;
import org.junit.Test;

/**
 * <p>AbstractRenderFormDelegate has no registered subclass and is never
 * invoked through the public FormEngine SPI, but it is a public class that
 * duplicates HtmlFormEngine's rendering logic, so its escaping behavior is
 * exercised directly here via a minimal test-only subclass.</p>
 */
public class AbstractRenderFormDelegateTest {

  protected static class RenderFormDelegate extends AbstractRenderFormDelegate {
    public String render(FormData formData) {
      return renderFormData(formData);
    }
  }

  @Test
  public void testTransformNullFormData() {
    assertNull(new RenderFormDelegate().render(null));
  }

  @Test
  public void testRenderFormFieldEscapesXssInLabel() {

    FormField formField = mock(FormField.class);
    when(formField.getId()).thenReturn("someField");
    when(formField.getLabel()).thenReturn("<img src=x onerror=alert(1)>");
    when(formField.getTypeName()).thenReturn(StringFormType.TYPE_NAME);
    when(formField.getValidationConstraints()).thenReturn(Collections.emptyList());
    when(formField.getDefaultValue()).thenReturn(null);

    TaskFormData taskFormData = mock(TaskFormData.class);
    when(taskFormData.getFormFields()).thenReturn(Collections.singletonList(formField));
    when(taskFormData.getFormProperties()).thenReturn(Collections.emptyList());

    String renderedForm = new RenderFormDelegate().render(taskFormData);

    assertTrue(renderedForm.contains("&lt;img src=x onerror=alert(1)&gt;"));
    assertFalse(renderedForm.contains("<img src=x onerror=alert(1)>"));

  }

  @Test
  public void testRenderFormFieldEscapesXssInEnumOption() {

    Map<String, String> enumValues = new LinkedHashMap<String, String>();
    enumValues.put("someKey", "<script>alert(1)</script>");

    FormField formField = mock(FormField.class);
    when(formField.getId()).thenReturn("someField");
    when(formField.getLabel()).thenReturn(null);
    when(formField.getTypeName()).thenReturn(EnumFormType.TYPE_NAME);
    when(formField.getType()).thenReturn(new EnumFormType(enumValues));
    when(formField.getValidationConstraints()).thenReturn(Collections.emptyList());
    when(formField.getDefaultValue()).thenReturn(null);

    TaskFormData taskFormData = mock(TaskFormData.class);
    when(taskFormData.getFormFields()).thenReturn(Collections.singletonList(formField));
    when(taskFormData.getFormProperties()).thenReturn(Collections.emptyList());

    String renderedForm = new RenderFormDelegate().render(taskFormData);

    assertTrue(renderedForm.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    assertFalse(renderedForm.contains("<script>alert(1)</script>"));

  }

  @Test
  public void testRenderFormFieldKeepsAngularExpressionsUnescaped() {

    FormField formField = mock(FormField.class);
    when(formField.getId()).thenReturn("someField");
    when(formField.getLabel()).thenReturn(null);
    when(formField.getTypeName()).thenReturn(StringFormType.TYPE_NAME);
    when(formField.getValidationConstraints()).thenReturn(Collections.emptyList());
    when(formField.getDefaultValue()).thenReturn(null);

    TaskFormData taskFormData = mock(TaskFormData.class);
    when(taskFormData.getFormFields()).thenReturn(Collections.singletonList(formField));
    when(taskFormData.getFormProperties()).thenReturn(Collections.emptyList());

    String renderedForm = new RenderFormDelegate().render(taskFormData);

    // the generated ng-if expression joins two checks with "&&"; it must stay literal
    // for Angular to evaluate it, not be HTML-escaped to "&amp;&amp;"
    assertTrue(renderedForm.contains("$invalid && "));
    assertFalse(renderedForm.contains("&amp;&amp;"));

  }

}
