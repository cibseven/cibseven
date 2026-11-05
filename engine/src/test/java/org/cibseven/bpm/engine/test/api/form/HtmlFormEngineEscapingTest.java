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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.FormType;
import org.cibseven.bpm.engine.impl.form.FormException;
import org.cibseven.bpm.engine.impl.form.FormFieldImpl;
import org.cibseven.bpm.engine.impl.form.FormFieldValidationConstraintImpl;
import org.cibseven.bpm.engine.impl.form.TaskFormDataImpl;
import org.cibseven.bpm.engine.impl.form.engine.HtmlDocumentBuilder;
import org.cibseven.bpm.engine.impl.form.engine.HtmlElementWriter;
import org.cibseven.bpm.engine.impl.form.engine.HtmlFormEngine;
import org.cibseven.bpm.engine.impl.form.type.BooleanFormType;
import org.cibseven.bpm.engine.impl.form.type.DateFormType;
import org.cibseven.bpm.engine.impl.form.type.EnumFormType;
import org.cibseven.bpm.engine.impl.form.type.StringFormType;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.bpm.engine.test.Deployment;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.Test;

/**
 * <p>CIB7-1753: untrusted values (form field ids, labels, default values, enum options,
 * validation constraint configuration, date patterns) must not be able to inject markup
 * or code into generated HTML forms.</p>
 *
 * <p>Values written as attribute values or text are HTML-escaped. Values that the client
 * interprets as code (the form field id inside AngularJS expressions and inside the date
 * picker script) cannot be protected by escaping and are therefore validated: only ids
 * made of letters, digits, '_', '$', '-' and '.' are rendered.</p>
 */
public class HtmlFormEngineEscapingTest extends PluggableProcessEngineTest {

  protected static final String[] UNSAFE_FORM_FIELD_IDS = {
      "field\"",            // ends the HTML attribute / a JavaScript string
      "field'",             // ends an AngularJS / JavaScript string
      "field<x",            // starts a tag
      "field>x",            // ends a tag
      "field</script>x",    // ends the form script block
      "{{field}}",          // AngularJS interpolation
      "field}}{{x",         // AngularJS interpolation
      "field)",             // ends a call in ng-click
      "field(x)",           // call
      "field[x]",           // member access
      "field;x",            // statement separator
      "field=x",            // assignment
      "field x",            // separate token
      "field\nx",           // line terminator (automatic semicolon insertion)
      "field/x",            // comment / regex / end tag
      "field&x",            // operator / character reference
      "field|x",            // AngularJS filter
      "field`x",            // template literal
      "field\\x",           // escape sequence
      "field#x",
      "field%x",
      "field+x",
      "field,x",
      "field?x",
      "field:x",
      "field!x",
      "field*x",
      "field^x",
      "field~x",
      "field@x",
      ""
  };

  protected static final String[] UNUSUAL_BUT_SAFE_FORM_FIELD_IDS = {
      "my-field",
      "my.field",
      "_field",
      "$field",
      "Feld_ä",
      "поле",
      "字段",
      "3rdField"
  };

  // HtmlElementWriter /////////////////////////////////////////////////////////////////////////

  @Test
  public void testAttributeValueIsEscaped() {
    String html = new HtmlDocumentBuilder(new HtmlElementWriter("e", true).attribute("a", "x\"y'z<w>&v"))
        .endElement()
        .getHtmlString();

    assertHtmlEquals("<e a=\"x&quot;y&#39;z&lt;w&gt;&amp;v\" />", html);
  }

  @Test
  public void testTextContentIsEscapedButKeepsQuotes() {
    String html = new HtmlDocumentBuilder(new HtmlElementWriter("e").textContent("it's \"quoted\" & <b>bold</b>"))
        .endElement()
        .getHtmlString();

    assertHtmlEquals("<e>it's \"quoted\" &amp; &lt;b&gt;bold&lt;/b&gt;</e>", html);
  }

  @Test
  public void testAttributeOverridesRawAttributeOfSameName() {
    String html = new HtmlDocumentBuilder(new HtmlElementWriter("e", true)
        .rawAttribute("a", "x&y")
        .attribute("a", "x&y"))
        .endElement()
        .getHtmlString();

    assertHtmlEquals("<e a=\"x&amp;y\" />", html);
  }

  @Test
  public void testRawAttributeOverridesAttributeOfSameName() {
    String html = new HtmlDocumentBuilder(new HtmlElementWriter("e", true)
        .attribute("a", "x&y")
        .rawAttribute("a", "x&y"))
        .endElement()
        .getHtmlString();

    assertHtmlEquals("<e a=\"x&y\" />", html);
  }

  @Test
  public void testTextContentOverridesRawTextContent() {
    String html = new HtmlDocumentBuilder(new HtmlElementWriter("e")
        .rawTextContent("<b>")
        .textContent("<b>"))
        .endElement()
        .getHtmlString();

    assertHtmlEquals("<e>&lt;b&gt;</e>", html);
  }

  @Test
  public void testAttributeNameIsValidated() {
    for (String name : Arrays.asList("a b", "a\"b", "a>b", "a=b", "a/b", "", null)) {
      assertThrows(IllegalArgumentException.class, () -> new HtmlElementWriter("e").attribute(name, "x"), "attribute name: " + name);
      assertThrows(IllegalArgumentException.class, () -> new HtmlElementWriter("e").rawAttribute(name, "x"), "attribute name: " + name);
    }
    // names used by the form engine and the report builders
    for (String name : Arrays.asList("class", "cam-variable-name", "uib-datepicker-popup", "ng-click", "is-open", "data-x", "xml:lang", "a.b", "_a")) {
      new HtmlElementWriter("e").attribute(name, "x");
    }
  }

  // HtmlFormEngine ///////////////////////////////////////////////////////////////////////////

  @Test
  public void testRejectsUnsafeFormFieldIdOfStringField() {
    for (String id : UNSAFE_FORM_FIELD_IDS) {
      FormException e = assertThrows(FormException.class, () -> render(formField(id, new StringFormType())), "id: " + id);
      assertTrue(e.getMessage().contains("Cannot render form field with id"), e.getMessage());
    }
  }

  @Test
  public void testRejectsUnsafeFormFieldIdOfDateField() {
    for (String id : UNSAFE_FORM_FIELD_IDS) {
      assertThrows(FormException.class, () -> render(formField(id, new DateFormType("dd.MM.yyyy"))), "id: " + id);
    }
  }

  @Test
  public void testRejectsUnsafeFormFieldIdOfEnumField() {
    for (String id : UNSAFE_FORM_FIELD_IDS) {
      assertThrows(FormException.class, () -> render(formField(id, new EnumFormType(Collections.singletonMap("a", "A")))), "id: " + id);
    }
  }

  @Test
  public void testRejectsUnsafeFormFieldIdOfReadOnlyDateField() {
    // read-only date fields do not render the date picker script, but still render AngularJS expressions
    for (String id : UNSAFE_FORM_FIELD_IDS) {
      FormFieldImpl formField = formField(id, new DateFormType("dd.MM.yyyy"));
      formField.getValidationConstraints().add(new FormFieldValidationConstraintImpl(HtmlFormEngine.CONSTRAINT_READONLY, null));
      assertThrows(FormException.class, () -> render(formField), "id: " + id);
    }
  }

  @Test
  public void testRejectsNullFormFieldId() {
    assertThrows(FormException.class, () -> render(formField(null, new StringFormType())));
  }

  @Test
  public void testRendersUnusualButSafeFormFieldIds() {
    for (String id : UNUSUAL_BUT_SAFE_FORM_FIELD_IDS) {
      String html = render(formField(id, new DateFormType("dd.MM.yyyy")));

      assertTrue(html.contains("name=\"" + id + "\""), html);
      assertTrue(html.contains("cam-variable-name=\"" + id + "\""), html);
      assertTrue(html.contains("is-open=\"dateFieldOpened" + id + "\""), html);
      assertTrue(html.contains("ng-click=\"open" + id + "($event)\""), html);
      assertTrue(html.contains("$scope.open" + id + " = function ($event)"), html);
      assertTrue(html.contains("ng-if=\"this.generatedForm." + id + ".$invalid && this.generatedForm." + id + ".$dirty\""), html);
    }
  }

  @Test
  public void testDatePatternIsEscaped() {
    // a quoted literal is valid in a SimpleDateFormat pattern and may contain anything
    String html = render(formField("dateField", new DateFormType("'<img src=x onerror=alert(1)>'dd.MM.yyyy")));

    assertFalse(html.contains("<img"), html);
    assertTrue(html.contains("uib-datepicker-popup=\"&#39;&lt;img src=x onerror=alert(1)&gt;&#39;dd.MM.yyyy\""), html);
    assertTrue(html.contains("the pattern ''&lt;img src=x onerror=alert(1)&gt;'dd.MM.yyyy'"), html);
    // the script is unaffected
    assertTrue(html.contains("$scope.opendateField = function ($event)"), html);
  }

  @Test
  public void testRejectsInterpolationMarkersInDatePattern() {
    assertThrows(FormException.class, () -> render(formField("dateField", new DateFormType("{{1}}dd.MM.yyyy"))));
    assertThrows(FormException.class, () -> render(formField("dateField", new DateFormType("dd.MM.yyyy}"))));
    assertThrows(FormException.class, () -> render(formField("dateField", new DateFormType("{dd.MM.yyyy"))));
  }

  @Test
  public void testDefaultValueIsEscaped() {
    FormFieldImpl formField = formField("field", new StringFormType());
    formField.setDefaultValue("\" onfocus=\"x");

    String html = render(formField);

    assertTrue(html.contains("value=\"&quot; onfocus=&quot;x\""), html);
    assertFalse(html.contains("onfocus=\"x"), html);
  }

  @Test
  public void testBooleanDefaultValueIsEscaped() {
    FormFieldImpl formField = formField("field", new BooleanFormType());
    formField.setDefaultValue("\"><b>");

    String html = render(formField);

    assertTrue(html.contains("type=\"checkbox\" value=\"&quot;&gt;&lt;b&gt;\""), html);
  }

  @Test
  public void testEnumOptionKeyAndTextAreEscaped() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("\"><b>", "<i>it's</i>");

    String html = render(formField("field", new EnumFormType(values)));

    assertTrue(html.contains("<option value=\"&quot;&gt;&lt;b&gt;\">"), html);
    assertTrue(html.contains("&lt;i&gt;it's&lt;/i&gt;"), html);
    assertFalse(html.contains("<b>"), html);
    assertFalse(html.contains("<i>"), html);
  }

  @Test
  public void testValidationConstraintConfigurationIsEscaped() {
    FormFieldImpl formField = formField("field", new StringFormType());
    formField.getValidationConstraints().add(new FormFieldValidationConstraintImpl("maxlength", "\"><img src=x onerror=alert(1)>"));

    String html = render(formField);

    assertTrue(html.contains("maxlength=\"&quot;&gt;&lt;img src=x onerror=alert(1)&gt;\""), html);
    assertFalse(html.contains("<img"), html);
  }

  @Test
  public void testLabelIsEscapedButKeepsQuotes() {
    FormFieldImpl formField = formField("field", new StringFormType());
    formField.setLabel("Tom & \"Jerry's\" <friends>");

    String html = render(formField);

    assertTrue(html.contains("Tom &amp; \"Jerry's\" &lt;friends&gt;"), html);
  }

  @Test
  public void testTypicalFormRendersAsBefore() {
    // reference output of the form engine before CIB7-1753 for a typical form
    FormFieldImpl stringField = formField("stringField", new StringFormType());
    stringField.setLabel("String Field");
    stringField.setDefaultValue("someString");
    stringField.getValidationConstraints().add(new FormFieldValidationConstraintImpl("required", "true"));
    stringField.getValidationConstraints().add(new FormFieldValidationConstraintImpl("maxlength", "10"));

    FormFieldImpl dateField = formField("dateField", new DateFormType("dd/MM/yyyy"));
    dateField.setLabel("Date Field");

    FormFieldImpl booleanField = formField("booleanField", new BooleanFormType());
    booleanField.setLabel("Boolean Field");
    booleanField.getValidationConstraints().add(new FormFieldValidationConstraintImpl("readonly", null));

    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("a", "A");
    values.put("b", "B");
    FormFieldImpl enumField = formField("enumField", new EnumFormType(values));
    enumField.setLabel("Enum Field");

    String html = render(stringField, dateField, booleanField, enumField);

    String expected = "<form name=\"generatedForm\" role=\"form\">"
        + "<div class=\"form-group\">"
        + "<label for=\"stringField\">String Field</label>"
        + "<input class=\"form-control\" name=\"stringField\" cam-variable-type=\"String\" cam-variable-name=\"stringField\" required=\"true\" maxlength=\"10\" type=\"text\" value=\"someString\" />"
        + "<div ng-if=\"this.generatedForm.stringField.$invalid && this.generatedForm.stringField.$dirty\" class=\"has-error\">"
        + "<div ng-show=\"this.generatedForm.stringField.$error.required\" class=\"help-block\">Required field</div>"
        + "<div ng-show=\"this.generatedForm.stringField.$error.camVariableType\" class=\"help-block\">Only a string value is allowed</div>"
        + "</div>"
        + "</div>"
        + "<div class=\"form-group\">"
        + "<label for=\"dateField\">Date Field</label>"
        + "<div class=\"input-group\">"
        + "<input class=\"form-control\" name=\"dateField\" cam-variable-type=\"String\" cam-variable-name=\"dateField\" type=\"text\" uib-datepicker-popup=\"dd/MM/yyyy\" is-open=\"dateFieldOpeneddateField\" />"
        + "<div class=\"input-group-btn\">"
        + "<button type=\"button\" class=\"btn btn-default\" ng-click=\"opendateField($event)\"><i class=\"glyphicon glyphicon-calendar\"></i></button>"
        + "</div>"
        + "<script cam-script type=\"text/form-script\">"
        + "$scope.opendateField = function ($event) { $event.preventDefault(); $event.stopPropagation(); $scope.dateFieldOpeneddateField = true; };"
        + "</script>"
        + "</div>"
        + "<div ng-if=\"this.generatedForm.dateField.$invalid && this.generatedForm.dateField.$dirty\" class=\"has-error\">"
        + "<div ng-show=\"this.generatedForm.dateField.$error.required && !this.generatedForm.dateField.$error.date\" class=\"help-block\">Required field</div>"
        + "<div ng-show=\"this.generatedForm.dateField.$error.date\" class=\"help-block\">Invalid date format: the date should have the pattern 'dd/MM/yyyy'</div>"
        + "</div>"
        + "</div>"
        + "<div class=\"form-group\">"
        + "<label for=\"booleanField\">Boolean Field</label>"
        + "<input class=\"form-control\" name=\"booleanField\" cam-variable-type=\"Boolean\" cam-variable-name=\"booleanField\" disabled type=\"checkbox\" />"
        + "<div ng-if=\"this.generatedForm.booleanField.$invalid && this.generatedForm.booleanField.$dirty\" class=\"has-error\">"
        + "<div ng-show=\"this.generatedForm.booleanField.$error.required\" class=\"help-block\">Required field</div>"
        + "<div ng-show=\"this.generatedForm.booleanField.$error.camVariableType\" class=\"help-block\">Only a boolean value is allowed</div>"
        + "</div>"
        + "</div>"
        + "<div class=\"form-group\">"
        + "<label for=\"enumField\">Enum Field</label>"
        + "<select class=\"form-control\" name=\"enumField\" cam-variable-type=\"String\" cam-variable-name=\"enumField\">"
        + "<option value=\"a\">A</option>"
        + "<option value=\"b\">B</option>"
        + "</select>"
        + "<div ng-if=\"this.generatedForm.enumField.$invalid && this.generatedForm.enumField.$dirty\" class=\"has-error\">"
        + "<div ng-show=\"this.generatedForm.enumField.$error.required\" class=\"help-block\">Required field</div>"
        + "<div ng-show=\"this.generatedForm.enumField.$error.camVariableType\" class=\"help-block\">Only a string value is allowed</div>"
        + "</div>"
        + "</div>"
        + "</form>";

    assertHtmlEquals(expected, html);
  }

  // AbstractRenderFormDelegate ///////////////////////////////////////////////////////////////

  @Test
  public void testDelegateRejectsUnsafeFormFieldIds() {
    for (String id : UNSAFE_FORM_FIELD_IDS) {
      assertThrows(FormException.class, () -> renderWithDelegate(formField(id, new StringFormType())), "id: " + id);
      assertThrows(FormException.class, () -> renderWithDelegate(formField(id, new DateFormType("dd.MM.yyyy"))), "id: " + id);
    }
  }

  @Test
  public void testDelegateRendersUnusualButSafeFormFieldIds() {
    for (String id : UNUSUAL_BUT_SAFE_FORM_FIELD_IDS) {
      String html = renderWithDelegate(formField(id, new DateFormType("dd.MM.yyyy")));

      assertTrue(html.contains("cam-variable-name=\"" + id + "\""), html);
      assertTrue(html.contains("$scope.open" + id + " = function ($event)"), html);
      assertTrue(html.contains("ng-if=\"this.generatedForm." + id + ".$invalid && this.generatedForm." + id + ".$dirty\""), html);
    }
  }

  @Test
  public void testDelegateEscapesAttributeValuesAndText() {
    FormFieldImpl formField = formField("field", new StringFormType());
    formField.setLabel("<img src=x onerror=alert(1)>");
    formField.setDefaultValue("\" onfocus=\"x");
    formField.getValidationConstraints().add(new FormFieldValidationConstraintImpl("maxlength", "\"><b>"));

    String html = renderWithDelegate(formField);

    assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    assertTrue(html.contains("value=\"&quot; onfocus=&quot;x\""), html);
    assertTrue(html.contains("maxlength=\"&quot;&gt;&lt;b&gt;\""), html);
    assertFalse(html.contains("<img"), html);
    assertFalse(html.contains("<b>"), html);
  }

  // end-to-end through BPMN deployment ////////////////////////////////////////////////////////

  @Deployment
  @Test
  public void testRenderStartFormRejectsUnsafeFormFieldId() {
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    FormException e = assertThrows(FormException.class, () -> formService.getRenderedStartForm(processDefinition.getId()));

    assertTrue(e.getMessage().contains("Cannot render form field with id"), e.getMessage());
  }

  @Deployment
  @Test
  public void testRenderStartFormEscapesUntrustedValues() {
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    String html = (String) formService.getRenderedStartForm(processDefinition.getId());

    // label
    assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    // default value
    assertTrue(html.contains("value=\"&quot;&gt;&lt;b&gt;\""), html);
    // validation constraint configuration
    assertTrue(html.contains("maxlength=\"&quot;&gt;&lt;img&gt;\""), html);
    // date pattern
    assertTrue(html.contains("uib-datepicker-popup=\"&#39;&lt;b&gt;&#39;dd.MM.yyyy\""), html);
    assertTrue(html.contains("the pattern ''&lt;b&gt;'dd.MM.yyyy'"), html);
    // enum option key and text
    assertTrue(html.contains("<option value=\"&quot;&gt;\">"), html);
    assertTrue(html.contains("&lt;i&gt;x&lt;/i&gt;"), html);
    // nothing got through
    assertFalse(html.contains("<img"), html);
    assertFalse(html.contains("<b>"), html);
    assertFalse(html.contains("<i>"), html);
    // code contexts are unchanged
    assertTrue(html.contains("$scope.opendateField = function ($event)"), html);
    assertTrue(html.contains("ng-if=\"this.generatedForm.dateField.$invalid && this.generatedForm.dateField.$dirty\""), html);
  }

  @Deployment
  @Test
  public void testRenderTaskFormEscapesLabelFromProcessVariable() {
    Map<String, Object> variables = new HashMap<String, Object>();
    variables.put("evilLabel", "<img src=x onerror=alert(1)>");
    runtimeService.startProcessInstanceByKey("HtmlFormEngineEscapingTest.labelFromVariable", variables);

    Task task = taskService.createTaskQuery().singleResult();

    String html = (String) formService.getRenderedTaskForm(task.getId());

    assertTrue(html.contains("<label for=\"field\">"), html);
    assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    assertFalse(html.contains("<img"), html);
  }

  @Deployment
  @Test
  public void testRenderStartFormWithUnusualFormFieldIds() {
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

    String html = (String) formService.getRenderedStartForm(processDefinition.getId());

    assertTrue(html.contains("name=\"my-field\""), html);
    assertTrue(html.contains("ng-if=\"this.generatedForm.my-field.$invalid && this.generatedForm.my-field.$dirty\""), html);
    assertTrue(html.contains("name=\"my.date\""), html);
    assertTrue(html.contains("$scope.openmy.date = function ($event)"), html);
  }

  // helper ///////////////////////////////////////////////////////////////////////////////////

  protected static FormFieldImpl formField(String id, FormType type) {
    FormFieldImpl formField = new FormFieldImpl();
    formField.setId(id);
    formField.setLabel(null);
    formField.setType(type);
    formField.setBusinessKey(false);
    formField.setValidationConstraints(new ArrayList<>());
    formField.setDefaultValue(null);
    return formField;
  }

  protected static TaskFormDataImpl formData(FormField... formFields) {
    TaskFormDataImpl formData = new TaskFormDataImpl();
    formData.setFormFields(Arrays.asList(formFields));
    formData.setFormProperties(Collections.emptyList());
    return formData;
  }

  protected static String render(FormField... formFields) {
    return (String) new HtmlFormEngine().renderTaskForm(formData(formFields));
  }

  protected static String renderWithDelegate(FormField... formFields) {
    return new AbstractRenderFormDelegateTest.RenderFormDelegate().render(formData(formFields));
  }

  protected static void assertHtmlEquals(String expected, String actual) {
    assertEquals(filterWhitespace(expected), filterWhitespace(actual));
  }

  protected static String filterWhitespace(String toFilter) {
    return toFilter.replaceAll("\\n", "").replaceAll("\\s", "");
  }

}
