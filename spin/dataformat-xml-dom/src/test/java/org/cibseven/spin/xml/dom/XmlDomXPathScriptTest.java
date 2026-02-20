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
package org.cibseven.spin.xml.dom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.cibseven.spin.SpinList;
import org.cibseven.spin.impl.test.Script;
import org.cibseven.spin.impl.test.ScriptTest;
import org.cibseven.spin.impl.test.ScriptVariable;
import org.cibseven.spin.xml.SpinXPathException;
import org.cibseven.spin.xml.SpinXPathQuery;
import org.cibseven.spin.xml.SpinXmlAttribute;
import org.cibseven.spin.xml.SpinXmlElement;
import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Menski
 */
public abstract class XmlDomXPathScriptTest extends ScriptTest {

  private static final String xml = "<root><child id=\"child\"><a id=\"a\"/><b id=\"b\"/><a id=\"c\"/></child></root>";
  private static final String xmlWithNamespace = "<root xmlns:bar=\"http://camunda.org\" xmlns:foo=\"http://camunda.com\"><foo:child id=\"child\"><bar:a id=\"a\"/><foo:b id=\"b\"/><a id=\"c\"/></foo:child></root>";
  private static final String xmlWithDefaultNamespace = "<root xmlns=\"http://camunda.com/example\" xmlns:bar=\"http://camunda.org\" xmlns:foo=\"http://camunda.com\"><foo:child id=\"child\"><bar:a id=\"a\"/><foo:b id=\"b\"/><a id=\"c\"/></foo:child></root>";

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsElement() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::element);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsElementList() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::elementList);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsAttribute() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::attribute);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsAttributeList() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::attributeList);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsString() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::string);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsNumber() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::number);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/")
      }
    )
  public void canNotQueryDocumentAsBoolean() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::bool);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child")
    }
  )
  public void canQueryElement() {
    SpinXPathQuery query = script.getVariable("query");
    SpinXmlElement child = query.element();
    assertThat(child.name()).isEqualTo("child");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/root/nonExisting")
      }
    )
  public void canNotQueryElement() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::element);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child")
    }
  )
  public void canNotQueryElementAsAttribute() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::attribute);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child/a")
    }
  )
  public void canQueryElementList() {
    SpinXPathQuery query = script.getVariable("query");
    SpinList<SpinXmlElement> childs = query.elementList();
    assertThat(childs).hasSize(2);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/root/child/nonExisting")
      }
    )
  public void canNotQueryElementList() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::elementList);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child/@id")
    }
  )
  public void canQueryAttribute() {
    SpinXPathQuery query = script.getVariable("query");
    SpinXmlAttribute attribute = query.attribute();
    assertThat(attribute.value()).isEqualTo("child");
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/root/child/@nonExisting")
      }
    )
  public void canNotQueryAttribute() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::attribute);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child/@id")
    }
  )
  public void canNotQueryAttributeAsElement() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::element);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "/root/child/a/@id")
    }
  )
  public void canQueryAttributeList() {
    SpinXPathQuery query = script.getVariable("query");
    SpinList<SpinXmlAttribute> attributes = query.attributeList();
    assertThat(attributes).hasSize(2);
  }

  @Test
  @Script(
      name = "XmlDomXPathScriptTest.xPath",
      variables = {
        @ScriptVariable(name = "input", value = xml),
        @ScriptVariable(name = "expression", value = "/root/child/a/@nonExisting")
      }
    )
  public void canNotQueryAttributeList() {
    SpinXPathQuery query = script.getVariable("query");
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(query::attributeList);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "string(/root/child/@id)")
    }
  )
  public void canQueryString() {
    SpinXPathQuery query = script.getVariable("query");
    String value = query.string();
    assertThat(value).isEqualTo("child");
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "string(/root/child/@nonExisting)")
    }
  )
  public void canQueryNonExistingString() {
    SpinXPathQuery query = script.getVariable("query");
    String value = query.string();
    assertThat(value).isEqualTo("");
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "string(/)")
    }
  )
  public void canQueryStringAsDocument() {
    SpinXPathQuery query = script.getVariable("query");
    String value = query.string();
    assertThat(value).isEqualTo("");
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "count(/root/child/a)")
    }
  )
  public void canQueryNumber() {
    SpinXPathQuery query = script.getVariable("query");
    Double count = query.number();
    assertThat(count).isEqualTo(2);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "count(/root/child/nonExisting)")
    }
  )
  public void canQueryNonExistingNumber() {
    SpinXPathQuery query = script.getVariable("query");
    Double count = query.number();
    assertThat(count).isEqualTo(0);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "count(/)")
    }
  )
  public void canQueryNumberAsDocument() {
    SpinXPathQuery query = script.getVariable("query");
    Double count = query.number();
    assertThat(count).isEqualTo(1);
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "boolean(/root/child)")
    }
  )
  public void canQueryBooleanTrue() {
    SpinXPathQuery query = script.getVariable("query");
    Boolean exists = query.bool();
    assertThat(exists).isTrue();
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "boolean(/root/nonExisting)")
    }
  )
  public void canQueryNonExistingBoolean() {
    SpinXPathQuery query = script.getVariable("query");
    Boolean exists = query.bool();
    assertThat(exists).isFalse();
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.xPath",
    variables = {
      @ScriptVariable(name = "input", value = xml),
      @ScriptVariable(name = "expression", value = "boolean(/)")
    }
  )
  public void canQueryBooleanAsDocument() {
    SpinXPathQuery query = script.getVariable("query");
    Boolean exists = query.bool();
    assertThat(exists).isTrue();
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.canQueryElementWithNamespace",
    variables = {
      @ScriptVariable(name = "input", value = xmlWithNamespace),
      @ScriptVariable(name = "expression", value = "/root/a:child")
    }
  )
  public void canQueryElementWithNamespace() {
    SpinXPathQuery query = script.getVariable("query");
    SpinXmlElement child = query.element();

    assertThat(child.name()).isEqualTo("child");
    assertThat(child.namespace()).isEqualTo("http://camunda.com");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }

  @Test
  @Script(
    variables = {
      @ScriptVariable(name = "input", value = xmlWithNamespace),
      @ScriptVariable(name = "expression", value = "/root/a:child")
    }
  )
  public void canQueryElementWithNamespaceMap() {
    SpinXPathQuery query = script.getVariable("query");
    SpinXmlElement child = query.element();

    assertThat(child.name()).isEqualTo("child");
    assertThat(child.namespace()).isEqualTo("http://camunda.com");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }

  @Test
  @Script(
    name = "XmlDomXPathScriptTest.canQueryElementWithNamespace",
    variables = {
      @ScriptVariable(name = "input", value = xmlWithDefaultNamespace),
      @ScriptVariable(name = "expression", value = "/:root/a:child")
    }
  )
  public void canQueryElementWithDefaultNamespace() {
    SpinXPathQuery query = script.getVariable("query");
    SpinXmlElement child = query.element();

    assertThat(child.name()).isEqualTo("child");
    assertThat(child.namespace()).isEqualTo("http://camunda.com");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }
}
