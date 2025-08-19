package org.cibseven.bpm.engine.impl.form;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.bpm.engine.form.FormField;
import org.cibseven.bpm.engine.form.FormFieldValidationConstraint;

public class FormFieldDto {
  protected Object value;
  protected Map<String, String> validationConstraints = new HashMap<String, String>();
  protected Map<String, String> properties = new HashMap<String, String>();

  public FormFieldDto(FormField formField) {
    value = formField.getValue();
    properties = formField.getProperties();
    for (FormFieldValidationConstraint constraint : formField.getValidationConstraints()) {
      String constraintValue = null;
      if (constraint.getConfiguration() != null) {
        // TODO: is configuration always a String?
        constraintValue = (String) constraint.getConfiguration();
      }
      validationConstraints.put(constraint.getName(), constraintValue);
    }
  }

  public Object getValue() {
    return value;
  }

  public void setValue(Object val) {
    value = val;
  }

  public Map<String, String> getValidationConstraints() {
    return validationConstraints;
  }

  public Map<String, String> getProperties() {
    return properties;
  }
}
