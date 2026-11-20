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
package org.cibseven.bpm.engine.impl.form.engine;


import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.form.FormData;
import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.FormFieldValidationConstraint;
import org.cibseven.bpm.engine.form.FormProperty;
import org.cibseven.bpm.engine.form.StartFormData;
import org.cibseven.bpm.engine.form.TaskFormData;
import org.cibseven.bpm.engine.impl.form.FormException;
import org.cibseven.bpm.engine.impl.form.type.BooleanFormType;
import org.cibseven.bpm.engine.impl.form.type.DateFormType;
import org.cibseven.bpm.engine.impl.form.type.EnumFormType;
import org.cibseven.bpm.engine.impl.form.type.StringFormType;

/**
 * <p>A simple {@link FormEngine} implementaiton which renders
 * forms as HTML such that they can be used as embedded forms
 * inside camunda Tasklist.</p>
 *
 * @author Daniel Meyer
 *
 */
public class HtmlFormEngine implements FormEngine {

  /* elements */
  protected static final String FORM_ELEMENT = "form";
  protected static final String DIV_ELEMENT = "div";
  protected static final String SPAN_ELEMENT = "span";
  protected static final String LABEL_ELEMENT = "label";
  protected static final String INPUT_ELEMENT = "input";
  protected static final String BUTTON_ELEMENT = "button";
  protected static final String SELECT_ELEMENT = "select";
  protected static final String OPTION_ELEMENT = "option";
  protected static final String I_ELEMENT = "i";
  protected static final String SCRIPT_ELEMENT = "script";

  /* attributes */
  protected static final String NAME_ATTRIBUTE = "name";
  protected static final String CLASS_ATTRIBUTE = "class";
  protected static final String ROLE_ATTRIBUTE = "role";
  protected static final String FOR_ATTRIBUTE = "for";
  protected static final String VALUE_ATTRIBUTE = "value";
  protected static final String TYPE_ATTRIBUTE = "type";
  protected static final String SELECTED_ATTRIBUTE = "selected";

  /* datepicker attributes*/
  protected static final String IS_OPEN_ATTRIBUTE = "is-open";
  protected static final String DATEPICKER_POPUP_ATTRIBUTE = "uib-datepicker-popup";

  /* camunda attributes */
  protected static final String CAM_VARIABLE_TYPE_ATTRIBUTE = "cam-variable-type";
  protected static final String CAM_VARIABLE_NAME_ATTRIBUTE = "cam-variable-name";
  protected static final String CAM_SCRIPT_ATTRIBUTE = "cam-script";
  protected static final String CAM_BUSINESS_KEY_ATTRIBUTE = "cam-business-key";

  /* angular attributes*/
  protected static final String NG_CLICK_ATTRIBUTE = "ng-click";
  protected static final String NG_IF_ATTRIBUTE = "ng-if";
  protected static final String NG_SHOW_ATTRIBUTE = "ng-show";

  /* classes */
  protected static final String FORM_GROUP_CLASS = "form-group";
  protected static final String FORM_CONTROL_CLASS = "form-control";
  protected static final String INPUT_GROUP_CLASS = "input-group";
  protected static final String INPUT_GROUP_BTN_CLASS = "input-group-btn";
  protected static final String BUTTON_DEFAULT_CLASS = "btn btn-default";
  protected static final String HAS_ERROR_CLASS = "has-error";
  protected static final String HELP_BLOCK_CLASS = "help-block";

  /* input[type] */
  protected static final String TEXT_INPUT_TYPE = "text";
  protected static final String CHECKBOX_INPUT_TYPE = "checkbox";

  /* button[type] */
  protected static final String BUTTON_BUTTON_TYPE = "button";

  /* script[type] */
  protected static final String TEXT_FORM_SCRIPT_TYPE = "text/form-script";

  /* glyphicons */
  protected static final String CALENDAR_GLYPHICON = "glyphicon glyphicon-calendar";

  /* generated form name */
  protected static final String GENERATED_FORM_NAME = "generatedForm";
  protected static final String FORM_ROLE = "form";

  /* error types */
  protected static final String REQUIRED_ERROR_TYPE = "required";
  protected static final String DATE_ERROR_TYPE = "date";

  /* form element selector */
  protected static final String FORM_ELEMENT_SELECTOR = "this." + GENERATED_FORM_NAME + ".%s";

  /* expressions */
  protected static final String INVALID_EXPRESSION = FORM_ELEMENT_SELECTOR + ".$invalid";
  protected static final String DIRTY_EXPRESSION = FORM_ELEMENT_SELECTOR + ".$dirty";
  protected static final String ERROR_EXPRESSION = FORM_ELEMENT_SELECTOR + ".$error";
  protected static final String DATE_ERROR_EXPRESSION = ERROR_EXPRESSION + ".date";
  protected static final String REQUIRED_ERROR_EXPRESSION = ERROR_EXPRESSION + ".required";
  protected static final String TYPE_ERROR_EXPRESSION = ERROR_EXPRESSION + ".camVariableType";

  /* JavaScript snippets */
  protected static final String DATE_FIELD_OPENED_ATTRIBUTE = "dateFieldOpened%s";
  protected static final String OPEN_DATEPICKER_SNIPPET = "$scope.open%s = function ($event) { $event.preventDefault(); $event.stopPropagation(); $scope.dateFieldOpened%s = true; };";
  protected static final String OPEN_DATEPICKER_FUNCTION_SNIPPET = "open%s($event)";

  /* messages */
  protected static final String REQUIRED_FIELD_MESSAGE = "Required field";
  protected static final String TYPE_FIELD_MESSAGE = "Only a %s value is allowed";
  protected static final String INVALID_DATE_FIELD_MESSAGE = "Invalid date format: the date should have the pattern ";

  protected static final String DATE_PATTERN_ATTRIBUTE = "datePattern";

  /* Form field ids are embedded into AngularJS expressions (ng-if, ng-show, ng-click,
   * is-open) and into the date picker form script, i.e. into code where HTML escaping
   * does not protect anything. Only ids made of letters, digits, '_', '$', '-' and '.'
   * are rendered: none of these characters can start or end a string literal, a
   * statement, a call, an assignment, a <script> block or an AngularJS interpolation. */
  protected static final Pattern SAFE_FORM_FIELD_ID_PATTERN = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}_$.\\-]+");

  /* The generated form is compiled by AngularJS in the legacy Tasklist, which evaluates
   * '{{ ... }}' in text and attribute values after the HTML entities were decoded, i.e.
   * HTML escaping does not protect against it. Dynamic values that contain the markers
   * are rejected instead (client-side template injection, CIB7-1753). */
  protected static final String INTERPOLATION_START_MARKER = "{{";
  protected static final String INTERPOLATION_END_MARKER = "}}";

  /* constraints */
  public static final String CONSTRAINT_READONLY = "readonly";
  public static final String CONSTRAINT_REQUIRED = "required";
  public static final String CONSTRAINT_DISABLED = "disabled";

  public String getName() {
    return "html";
  }

  public Object renderStartForm(StartFormData startForm) {
    return renderFormData(startForm);
  }

  public Object renderTaskForm(TaskFormData taskForm) {
    return renderFormData(taskForm);
  }

  protected String renderFormData(FormData formData) {

    if(formData == null
        || (formData.getFormFields() == null || formData.getFormFields().isEmpty())
        && (formData.getFormProperties() == null || formData.getFormProperties().isEmpty())) {
      return null;

    } else {
      HtmlElementWriter formElement = new HtmlElementWriter(FORM_ELEMENT)
          .attribute(NAME_ATTRIBUTE, GENERATED_FORM_NAME)
          .attribute(ROLE_ATTRIBUTE, FORM_ROLE);

      HtmlDocumentBuilder documentBuilder = new HtmlDocumentBuilder(formElement);

      // render fields
      for (FormField formField : formData.getFormFields()) {
        renderFormField(formField, documentBuilder);
      }

      // render deprecated form properties
      for (FormProperty formProperty : formData.getFormProperties()) {
        renderFormField(new FormPropertyAdapter(formProperty), documentBuilder);
      }

      // end document element
      documentBuilder.endElement();

      return documentBuilder.getHtmlString();

    }
  }

  protected void renderFormField(FormField formField, HtmlDocumentBuilder documentBuilder) {
    // start group
    HtmlElementWriter divElement = new HtmlElementWriter(DIV_ELEMENT)
        .attribute(CLASS_ATTRIBUTE, FORM_GROUP_CLASS);

    documentBuilder.startElement(divElement);

    // fail before anything is rendered for an id that cannot be embedded into the form's scripts / expressions
    String formFieldId = validateFormFieldId(formField.getId());
    String formFieldLabel = validateNoInterpolation(formField.getLabel(), "label", formFieldId);

    // write label
    if (formFieldLabel != null && !formFieldLabel.isEmpty()) {

      HtmlElementWriter labelElement = new HtmlElementWriter(LABEL_ELEMENT)
          .attribute(FOR_ATTRIBUTE, formFieldId)
          .textContent(formFieldLabel);

      // <label for="...">...</label>
      documentBuilder.startElement(labelElement).endElement();
    }

    // render form control
    if(isEnum(formField)) {
      // <select ...>
      renderSelectBox(formField, documentBuilder);

    } else if (isDate(formField)){

      renderDatePicker(formField, documentBuilder);

    } else {
      // <input ...>
      renderInputField(formField, documentBuilder);

    }

    renderInvalidMessageElement(formField, documentBuilder);

    // end group
    documentBuilder.endElement();
  }

  protected HtmlElementWriter createInputField(FormField formField) {
    HtmlElementWriter inputField = new HtmlElementWriter(INPUT_ELEMENT, true);

    addCommonFormFieldAttributes(formField, inputField);

    inputField.attribute(TYPE_ATTRIBUTE, TEXT_INPUT_TYPE);

    return inputField;
  }

  protected void renderDatePicker(FormField formField, HtmlDocumentBuilder documentBuilder) {
    boolean isReadOnly = isReadOnly(formField);

    // start input-group
    HtmlElementWriter inputGroupDivElement = new HtmlElementWriter(DIV_ELEMENT)
        .attribute(CLASS_ATTRIBUTE, INPUT_GROUP_CLASS);

    String formFieldId = validateFormFieldId(formField.getId());

    // <div>
    documentBuilder.startElement(inputGroupDivElement);

    // input field
    HtmlElementWriter inputField = createInputField(formField);

    String dateFormat = validateDatePattern((String) formField.getType().getInformation(DATE_PATTERN_ATTRIBUTE), formFieldId);
    if(!isReadOnly) {
      inputField
          .attribute(DATEPICKER_POPUP_ATTRIBUTE, dateFormat)
          // AngularJS expression: constant text plus the validated form field id
          .rawAttribute(IS_OPEN_ATTRIBUTE, String.format(DATE_FIELD_OPENED_ATTRIBUTE, formFieldId));
    }

    // <input ... />
    documentBuilder
        .startElement(inputField)
        .endElement();


    // if form field is read only, do not render date picker open button
    if(!isReadOnly) {

      // input addon
      HtmlElementWriter addonElement = new HtmlElementWriter(DIV_ELEMENT)
          .attribute(CLASS_ATTRIBUTE, INPUT_GROUP_BTN_CLASS);

      // <div>
      documentBuilder.startElement(addonElement);

      // button to open date picker
      HtmlElementWriter buttonElement = new HtmlElementWriter(BUTTON_ELEMENT)
          .attribute(TYPE_ATTRIBUTE, BUTTON_BUTTON_TYPE)
          .attribute(CLASS_ATTRIBUTE, BUTTON_DEFAULT_CLASS)
          // AngularJS expression: constant text plus the validated form field id
          .rawAttribute(NG_CLICK_ATTRIBUTE, String.format(OPEN_DATEPICKER_FUNCTION_SNIPPET, formFieldId));

      // <button>
      documentBuilder.startElement(buttonElement);

      HtmlElementWriter iconElement = new HtmlElementWriter(I_ELEMENT)
          .attribute(CLASS_ATTRIBUTE, CALENDAR_GLYPHICON);

      // <i ...></i>
      documentBuilder
          .startElement(iconElement)
          .endElement();

      // </button>
      documentBuilder.endElement();

      // </div>
      documentBuilder.endElement();


      HtmlElementWriter scriptElement = new HtmlElementWriter(SCRIPT_ELEMENT)
          .attribute(CAM_SCRIPT_ATTRIBUTE, null)
          .attribute(TYPE_ATTRIBUTE, TEXT_FORM_SCRIPT_TYPE)
          // JavaScript executed by the client: constant text plus the validated form field id
          .rawTextContent(String.format(OPEN_DATEPICKER_SNIPPET, formFieldId, formFieldId));

      // <script ...> </script>
      documentBuilder
          .startElement(scriptElement)
          .endElement();

    }

    // </div>
    documentBuilder.endElement();

  }

  protected void renderInputField(FormField formField, HtmlDocumentBuilder documentBuilder) {
    HtmlElementWriter inputField = new HtmlElementWriter(INPUT_ELEMENT, true);
    addCommonFormFieldAttributes(formField, inputField);

    String inputType = !isBoolean(formField) ? TEXT_INPUT_TYPE : CHECKBOX_INPUT_TYPE;

    inputField.attribute(TYPE_ATTRIBUTE, inputType);

    // add default value
    Object defaultValue = formField.getDefaultValue();
    if(defaultValue != null) {
      inputField.attribute(VALUE_ATTRIBUTE, validateNoInterpolation(defaultValue.toString(), "default value", formField.getId()));
    }

    // <input ... />
    documentBuilder.startElement(inputField).endElement();
  }

  protected void renderSelectBox(FormField formField, HtmlDocumentBuilder documentBuilder) {
    HtmlElementWriter selectBox = new HtmlElementWriter(SELECT_ELEMENT, false);

    addCommonFormFieldAttributes(formField, selectBox);

    // <select ...>
    documentBuilder.startElement(selectBox);

    // <option ...>
    renderSelectOptions(formField, documentBuilder);

    // </select>
    documentBuilder.endElement();
  }

  protected void renderSelectOptions(FormField formField, HtmlDocumentBuilder documentBuilder) {
    EnumFormType enumFormType = (EnumFormType) formField.getType();
    Map<String, String> values = enumFormType.getValues();
    String formFieldId = formField.getId();

    for (Entry<String, String> value : values.entrySet()) {
      // <option>
      HtmlElementWriter option = new HtmlElementWriter(OPTION_ELEMENT, false)
          .attribute(VALUE_ATTRIBUTE, validateNoInterpolation(value.getKey(), "enum value id", formFieldId))
          .textContent(validateNoInterpolation(value.getValue(), "enum value name", formFieldId));

      documentBuilder.startElement(option).endElement();
    }
  }

  protected void renderInvalidMessageElement(FormField formField, HtmlDocumentBuilder documentBuilder) {
    HtmlElementWriter divElement = new HtmlElementWriter(DIV_ELEMENT);

    String formFieldId = validateFormFieldId(formField.getId());
    String ifExpression = String.format(INVALID_EXPRESSION + " && " + DIRTY_EXPRESSION, formFieldId, formFieldId);

    divElement
        // AngularJS expression: constant text plus the validated form field id
        .rawAttribute(NG_IF_ATTRIBUTE, ifExpression)
        .attribute(CLASS_ATTRIBUTE, HAS_ERROR_CLASS);

    // <div ng-if="....$invalid && ....$dirty"...>
    documentBuilder.startElement(divElement);

    if (!isDate(formField)) {
      renderInvalidValueMessage(formField, documentBuilder);
      renderInvalidTypeMessage(formField, documentBuilder);

    } else {
      renderInvalidDateMessage(formField, documentBuilder);
    }

    documentBuilder.endElement();
  }

  protected void renderInvalidValueMessage(FormField formField, HtmlDocumentBuilder documentBuilder) {
    HtmlElementWriter divElement = new HtmlElementWriter(DIV_ELEMENT);

    String formFieldId = validateFormFieldId(formField.getId());

    String expression = String.format(REQUIRED_ERROR_EXPRESSION, formFieldId);

    divElement
        // AngularJS expression: constant text plus the validated form field id
        .rawAttribute(NG_SHOW_ATTRIBUTE, expression)
        .attribute(CLASS_ATTRIBUTE, HELP_BLOCK_CLASS)
        .textContent(REQUIRED_FIELD_MESSAGE);

    documentBuilder
        .startElement(divElement)
        .endElement();
  }

  protected void renderInvalidTypeMessage(FormField formField, HtmlDocumentBuilder documentBuilder) {
    HtmlElementWriter divElement = new HtmlElementWriter(DIV_ELEMENT);

    String formFieldId = validateFormFieldId(formField.getId());

    String expression = String.format(TYPE_ERROR_EXPRESSION, formFieldId);

    String typeName = formField.getTypeName();

    if (isEnum(formField)) {
      typeName = StringFormType.TYPE_NAME;
    }

    divElement
        // AngularJS expression: constant text plus the validated form field id
        .rawAttribute(NG_SHOW_ATTRIBUTE, expression)
        .attribute(CLASS_ATTRIBUTE, HELP_BLOCK_CLASS)
        .textContent(String.format(TYPE_FIELD_MESSAGE, validateNoInterpolation(typeName, "type name", formFieldId)));

    documentBuilder
        .startElement(divElement)
        .endElement();
  }

  protected void renderInvalidDateMessage(FormField formField, HtmlDocumentBuilder documentBuilder) {
    String formFieldId = validateFormFieldId(formField.getId());

    HtmlElementWriter firstDivElement = new HtmlElementWriter(DIV_ELEMENT);

    String firstExpression = String.format(REQUIRED_ERROR_EXPRESSION + " && !" + DATE_ERROR_EXPRESSION, formFieldId, formFieldId);

    firstDivElement
        // AngularJS expression: constant text plus the validated form field id
        .rawAttribute(NG_SHOW_ATTRIBUTE, firstExpression)
        .attribute(CLASS_ATTRIBUTE, HELP_BLOCK_CLASS)
        .textContent(REQUIRED_FIELD_MESSAGE);

    documentBuilder
        .startElement(firstDivElement)
        .endElement();

    HtmlElementWriter secondDivElement = new HtmlElementWriter(DIV_ELEMENT);

    String secondExpression = String.format(DATE_ERROR_EXPRESSION, formFieldId);

    secondDivElement
        // AngularJS expression: constant text plus the validated form field id
        .rawAttribute(NG_SHOW_ATTRIBUTE, secondExpression)
        .attribute(CLASS_ATTRIBUTE, HELP_BLOCK_CLASS)
        .textContent(INVALID_DATE_FIELD_MESSAGE + "'" + validateDatePattern(String.valueOf(formField.getType().getInformation(DATE_PATTERN_ATTRIBUTE)), formFieldId) + "'");

    documentBuilder
        .startElement(secondDivElement)
        .endElement();
  }

  protected void addCommonFormFieldAttributes(FormField formField, HtmlElementWriter formControl) {

    String formFieldId = formField.getId();

    String typeName = validateNoInterpolation(formField.getTypeName(), "type name", formFieldId);

    if (isEnum(formField) || isDate(formField)) {
      typeName = StringFormType.TYPE_NAME;
    }

    typeName = typeName.substring(0, 1).toUpperCase() + typeName.substring(1);

    formControl
        .attribute(CLASS_ATTRIBUTE, FORM_CONTROL_CLASS)
        .attribute(NAME_ATTRIBUTE, formFieldId);

    if (!formField.isBusinessKey()) {
      formControl
          .attribute(CAM_VARIABLE_TYPE_ATTRIBUTE, typeName)
          .attribute(CAM_VARIABLE_NAME_ATTRIBUTE, formFieldId);
    }
    else {
      formControl.attribute(CAM_BUSINESS_KEY_ATTRIBUTE, null);
    }

    // add validation constraints
    for (FormFieldValidationConstraint constraint : formField.getValidationConstraints()) {
      String constraintName = validateNoInterpolation(constraint.getName(), "validation constraint name", formFieldId);
      String configuration = validateNoInterpolation((String) constraint.getConfiguration(),
          "configuration of the validation constraint '" + constraintName + "'", formFieldId);
      formControl.attribute(CONSTRAINT_READONLY.equals(constraintName) ? CONSTRAINT_DISABLED : constraintName, configuration);
    }
  }

  // helper /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Validates that a dynamic value can be written as text or attribute value of a generated
   * HTML form. The value is HTML-escaped when written, but the generated form is compiled by
   * AngularJS (legacy Tasklist), which evaluates interpolation markers (<code>{{</code> ...
   * <code>}}</code>) in text and attribute values after the HTML entities were decoded, i.e.
   * escaping does not prevent the injected expression from being executed.
   *
   * @param value the value to validate, <code>null</code> is allowed and returned as is
   * @param what describes the value in the exception message, e.g. "label"
   * @param formFieldId the id of the form field the value belongs to, for the exception message
   * @return the value, if it does not contain <code>{{</code> or <code>}}</code>
   * @throws FormException otherwise
   */
  public static String validateNoInterpolation(String value, String what, String formFieldId) {
    if (value != null && (value.contains(INTERPOLATION_START_MARKER) || value.contains(INTERPOLATION_END_MARKER))) {
      throw new FormException("Cannot render form field '" + formFieldId + "' as HTML form: the " + what + " '" + value
          + "' contains the AngularJS interpolation markers '" + INTERPOLATION_START_MARKER + "' or '" + INTERPOLATION_END_MARKER + "'.");
    }
    return value;
  }

  /**
   * Validates that a form field id can be embedded into the AngularJS expressions and the
   * form script of a generated HTML form, where HTML escaping does not apply.
   *
   * @return the id, if it only consists of letters, digits, '_', '$', '-' and '.'
   * @throws FormException otherwise
   */
  public static String validateFormFieldId(String formFieldId) {
    if (formFieldId == null || !SAFE_FORM_FIELD_ID_PATTERN.matcher(formFieldId).matches()) {
      throw new FormException("Cannot render form field with id '" + formFieldId + "' as HTML form: "
          + "the id of a form field may only contain letters, digits, '_', '$', '-' and '.'.");
    }
    return formFieldId;
  }

  /**
   * Validates that a date pattern can be embedded into a generated HTML form. Like every
   * other dynamic value it must not contain AngularJS interpolation markers (see
   * {@link #validateNoInterpolation(String, String, String)}); as a date pattern has no
   * use for braces at all, single '{' and '}' are rejected as well.
   *
   * @return the pattern, if it does not contain '{' or '}'
   * @throws FormException otherwise
   */
  public static String validateDatePattern(String datePattern, String formFieldId) {
    validateNoInterpolation(datePattern, "date pattern", formFieldId);
    if (datePattern != null && (datePattern.indexOf('{') >= 0 || datePattern.indexOf('}') >= 0)) {
      throw new FormException("Cannot render form field '" + formFieldId + "' as HTML form: "
          + "the date pattern '" + datePattern + "' must not contain '{' or '}'.");
    }
    return datePattern;
  }

  protected boolean isEnum(FormField formField) {
    return EnumFormType.TYPE_NAME.equals(formField.getTypeName());
  }

  protected boolean isDate(FormField formField) {
    return DateFormType.TYPE_NAME.equals(formField.getTypeName());
  }

  protected boolean isBoolean(FormField formField) {
    return BooleanFormType.TYPE_NAME.equals(formField.getTypeName());
  }

  protected boolean isReadOnly(FormField formField) {
    List<FormFieldValidationConstraint> validationConstraints = formField.getValidationConstraints();
    if(validationConstraints != null) {
      for (FormFieldValidationConstraint validationConstraint : validationConstraints) {
        if(CONSTRAINT_READONLY.equals(validationConstraint.getName())){
          return true;
        }
      }
    }
    return false;
  }

}
