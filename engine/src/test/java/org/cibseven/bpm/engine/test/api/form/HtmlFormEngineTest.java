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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.TaskFormData;
import org.cibseven.bpm.engine.impl.form.engine.FormEngine;
import org.cibseven.bpm.engine.impl.form.engine.HtmlDocumentBuilder;
import org.cibseven.bpm.engine.impl.form.engine.HtmlElementWriter;
import org.cibseven.bpm.engine.impl.form.engine.HtmlFormEngine;
import org.cibseven.bpm.engine.impl.form.type.EnumFormType;
import org.cibseven.bpm.engine.impl.form.type.StringFormType;
import org.cibseven.bpm.engine.impl.util.IoUtil;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.Test;

/**
 * @author Daniel Meyer
 *
 */
public class HtmlFormEngineTest extends PluggableProcessEngineTest {

  @Test
  public void testIsDefaultFormEngine() {

    // make sure the html form engine is the default form engine:
    Map<String, FormEngine> formEngines = processEngineConfiguration.getFormEngines();
    assertTrue(formEngines.get(null) instanceof HtmlFormEngine);

  }

  @Test
  public void testTransformNullFormData() {
    HtmlFormEngine formEngine = new HtmlFormEngine();
    assertNull(formEngine.renderStartForm(null));
    assertNull(formEngine.renderTaskForm(null));
  }

  @Test
  public void testHtmlElementWriter() {

    String htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName></someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName", true))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName />", htmlString);

    htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName", true).attribute("someAttr", "someAttrValue"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName someAttr=\"someAttrValue\" />", htmlString);

    htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName").attribute("someAttr", "someAttrValue"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName someAttr=\"someAttrValue\"></someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName").attribute("someAttr", null))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName someAttr></someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(new HtmlElementWriter("someTagName").textContent("someTextContent"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName>someTextContent</someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName"))
          .startElement(new HtmlElementWriter("someChildTag"))
          .endElement()
        .endElement()
    .getHtmlString();
    assertHtmlEquals("<someTagName><someChildTag></someChildTag></someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName"))
          .startElement(new HtmlElementWriter("someChildTag").textContent("someTextContent"))
          .endElement()
        .endElement()
    .getHtmlString();
    assertHtmlEquals("<someTagName><someChildTag>someTextContent</someChildTag></someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName").textContent("someTextContent"))
          .startElement(new HtmlElementWriter("someChildTag"))
          .endElement()
        .endElement()
    .getHtmlString();
    assertHtmlEquals("<someTagName><someChildTag></someChildTag>someTextContent</someTagName>", htmlString);

    // invalid usage

    try {
      new HtmlElementWriter("sometagname", true).textContent("sometextcontet");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("Self-closing element cannot have text content"));
    }

  }

  @Test
  public void testHtmlElementWriterEscapesContent() {

    // text content is HTML-escaped (prevents stored XSS via form field labels / enum options)
    String htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName").textContent("<script>alert(1)</script>"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName>&lt;script&gt;alert(1)&lt;/script&gt;</someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName").textContent("<img src=x onerror=alert(1)>"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName>&lt;img src=x onerror=alert(1)&gt;</someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName").textContent("a & b"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName>a &amp; b</someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName", true).attribute("someAttr", "\"><script>alert(1)</script>"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName someAttr=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\" />", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName").rawTextContent("a && b"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName>a && b</someTagName>", htmlString);

    htmlString = new HtmlDocumentBuilder(
        new HtmlElementWriter("someTagName", true).rawAttribute("someAttr", "a && b"))
      .endElement()
      .getHtmlString();
    assertHtmlEquals("<someTagName someAttr=\"a && b\" />", htmlString);

  }

  @Deployment
  @Test
  public void testRenderEmptyStartForm() {

    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    assertNull(formService.getRenderedStartForm(processDefinition.getId()));

  }

  @Deployment
  @Test
  public void testRenderStartForm() {

    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    String renderedForm = (String) formService.getRenderedStartForm(processDefinition.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testRenderStartForm.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testRenderEnumField() {

    runtimeService.startProcessInstanceByKey("HtmlFormEngineTest.testRenderEnumField");

    Task t = taskService.createTaskQuery()
      .singleResult();

    String renderedForm = (String) formService.getRenderedTaskForm(t.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testRenderEnumField.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testRenderTaskForm() {

    runtimeService.startProcessInstanceByKey("HtmlFormEngineTest.testRenderTaskForm");

    Task t = taskService.createTaskQuery()
      .singleResult();

    String renderedForm = (String) formService.getRenderedTaskForm(t.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testRenderTaskForm.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testRenderDateField() {

    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();
    String renderedForm = (String) formService.getRenderedStartForm(processDefinition.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testRenderDateField.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testRenderDateFieldWithPattern() {

    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();
    String renderedForm = (String) formService.getRenderedStartForm(processDefinition.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testRenderDateFieldWithPattern.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testLegacyFormPropertySupport() {

    runtimeService.startProcessInstanceByKey("HtmlFormEngineTest.testLegacyFormPropertySupport");

    Task t = taskService.createTaskQuery()
      .singleResult();

    String renderedForm = (String) formService.getRenderedTaskForm(t.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testLegacyFormPropertySupport.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testLegacyFormPropertySupportReadOnly() {

    runtimeService.startProcessInstanceByKey("HtmlFormEngineTest.testLegacyFormPropertySupportReadOnly");

    Task t = taskService.createTaskQuery()
      .singleResult();

    String renderedForm = (String) formService.getRenderedTaskForm(t.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testLegacyFormPropertySupportReadOnly.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testLegacyFormPropertySupportRequired() {

    runtimeService.startProcessInstanceByKey("HtmlFormEngineTest.testLegacyFormPropertySupportRequired");

    Task t = taskService.createTaskQuery()
      .singleResult();

    String renderedForm = (String) formService.getRenderedTaskForm(t.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testLegacyFormPropertySupportRequired.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Deployment
  @Test
  public void testBusinessKey() {

    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    String renderedForm = (String) formService.getRenderedStartForm(processDefinition.getId());

    String expectedForm = IoUtil.readClasspathResourceAsString("org/cibseven/bpm/engine/test/api/form/HtmlFormEngineTest.testBusinessKey.html");

    assertHtmlEquals(expectedForm, renderedForm);

  }

  @Test
  public void testRenderFormFieldEscapesXssInLabel() {

    FormField formField = mock(FormField.class);
    when(formField.getId()).thenReturn("someField");
    when(formField.getLabel()).thenReturn("<img src=x onerror=alert(1)>");
    when(formField.getTypeName()).thenReturn(StringFormType.TYPE_NAME);
    when(formField.isBusinessKey()).thenReturn(false);
    when(formField.getValidationConstraints()).thenReturn(Collections.emptyList());
    when(formField.getDefaultValue()).thenReturn(null);

    TaskFormData taskFormData = mock(TaskFormData.class);
    when(taskFormData.getFormFields()).thenReturn(Collections.singletonList(formField));
    when(taskFormData.getFormProperties()).thenReturn(Collections.emptyList());

    String renderedForm = (String) new HtmlFormEngine().renderTaskForm(taskFormData);

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
    when(formField.isBusinessKey()).thenReturn(false);
    when(formField.getValidationConstraints()).thenReturn(Collections.emptyList());
    when(formField.getDefaultValue()).thenReturn(null);

    TaskFormData taskFormData = mock(TaskFormData.class);
    when(taskFormData.getFormFields()).thenReturn(Collections.singletonList(formField));
    when(taskFormData.getFormProperties()).thenReturn(Collections.emptyList());

    String renderedForm = (String) new HtmlFormEngine().renderTaskForm(taskFormData);

    assertTrue(renderedForm.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    assertFalse(renderedForm.contains("<script>alert(1)</script>"));

  }

  public void assertHtmlEquals(String expected, String actual) {
    assertEquals(filterWhitespace(expected), filterWhitespace(actual));
  }

  protected String filterWhitespace(String tofilter) {
    return tofilter.replaceAll("\\n", "").replaceAll("\\s", "");
  }

}
