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
import static org.cibseven.spin.Spin.S;

import java.util.HashMap;
import java.util.Map;

import org.cibseven.spin.SpinList;
import org.cibseven.spin.xml.SpinXPathException;
import org.cibseven.spin.xml.SpinXmlAttribute;
import org.cibseven.spin.xml.SpinXmlElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Menski
 */
public class XmlDomXPathTest {

  protected SpinXmlElement element;
  protected SpinXmlElement elementWithNamespace;
  protected SpinXmlElement elementWithDefaultNamespace;

  @BeforeEach
  public void parseXml() {
    element = S("<root><child id=\"child\"><a id=\"a\"/><b id=\"b\"/><a id=\"c\"/></child></root>");
    elementWithNamespace = S("<root xmlns:bar=\"http://camunda.org\" xmlns:foo=\"http://camunda.com\"><foo:child id=\"child\"><bar:a id=\"a\"/><foo:b id=\"b\"/><a id=\"c\"/></foo:child></root>");
    elementWithDefaultNamespace = S("<root xmlns=\"http://camunda.com/example\" xmlns:bar=\"http://camunda.org\" xmlns:foo=\"http://camunda.com\"><foo:child id=\"child\"><bar:a id=\"a\"/><foo:b id=\"b\"/><a id=\"c\"/></foo:child></root>");
  }

  @Test
  public void canNotQueryDocumentAsElement() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").element());
  }

  @Test
  public void canNotQueryDocumentAsElementList() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").elementList());
  }

  @Test
  public void canNotQueryDocumentAsAttribute() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").attribute());
  }

  @Test
  public void canNotQueryDocumentAsAttributeList() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").attributeList());
  }

  @Test
  public void canNotQueryDocumentAsString() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").string());
  }

  @Test
  public void canNotQueryDocumentAsNumber() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").number());
  }

  @Test
  public void canNotQueryDocumentAsBoolean() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/").bool());
  }

  @Test
  public void canQueryElement() {
    SpinXmlElement child = element.xPath("/root/child").element();
    assertThat(child.name()).isEqualTo("child");
    assertThat(child.attr("id").value()).isEqualTo("child");

    SpinXmlElement b = child.xPath("b").element();
    assertThat(b.name()).isEqualTo("b");
    assertThat(b.attr("id").value()).isEqualTo("b");
  }

  @Test
  public void canNotQueryElement() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/nonExisting").element());
  }

  @Test
  public void canNotQueryElementAsAttribute() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/child/").attribute());
  }

  @Test
  public void canQueryElementList() {
    SpinList<SpinXmlElement> childs = element.xPath("/root/child/a").elementList();
    assertThat(childs).hasSize(2);
  }

  @Test
  public void canNotQueryElementList() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/child/nonExisting").elementList());
  }

  @Test
  public void canQueryAttribute() {
    SpinXmlAttribute attribute = element.xPath("/root/child/@id").attribute();
    assertThat(attribute.value()).isEqualTo("child");
  }

  @Test
  public void canNotQueryAttribute() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/child/@nonExisting").attribute());
  }

  @Test
  public void canNotQueryAttributeAsElement() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/child/@id").element());
  }

  @Test
  public void canQueryAttributeList() {
    SpinList<SpinXmlAttribute> attributes = element.xPath("/root/child/a/@id").attributeList();
    assertThat(attributes).hasSize(2);
  }

  @Test
  public void canNotQueryAttributeList() {
    assertThatExceptionOfType(SpinXPathException.class).isThrownBy(() -> element.xPath("/root/child/a/@nonExisting").attributeList());
  }

  @Test
  public void canQueryString() {
    String value = element.xPath("string(/root/child/@id)").string();
    assertThat(value).isEqualTo("child");

    // can query not existing string
    value = element.xPath("string(/root/child/@nonExisting)").string();
    assertThat(value).isEqualTo("");

    // can query string as document
    value = element.xPath("string(/)").string();
    assertThat(value).isEqualTo("");
  }

  @Test
  public void canQueryNumber() {
    Double count = element.xPath("count(/root/child/a)").number();
    assertThat(count).isEqualTo(2);

    // can query not existing number
    count = element.xPath("count(/root/child/nonExisting)").number();
    assertThat(count).isEqualTo(0);

    // can query number as document
    count = element.xPath("count(/)").number();
    assertThat(count).isEqualTo(1);
  }

  @Test
  public void canQueryBoolean() {
    Boolean exists = element.xPath("boolean(/root/child)").bool();
    assertThat(exists).isTrue();

    // can query not existing boolean
    exists = element.xPath("boolean(/root/nonExisting)").bool();
    assertThat(exists).isFalse();

    // can query boolean as document
    exists = element.xPath("boolean(/)").bool();
    assertThat(exists).isTrue();
  }

  @Test
  public void canQueryElementWithNamespace() {
    SpinXmlElement child = elementWithNamespace.xPath("/root/a:child")
      .ns("a", "http://camunda.com")
      .element();

    assertThat(child.name()).isEqualTo("child");
    assertThat(child.namespace()).isEqualTo("http://camunda.com");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }

  @Test
  public void canQueryElementWithNamespaceMap() {
    Map<String, String> namespaces = new HashMap<String, String>();
    namespaces.put("a", "http://camunda.com");
    namespaces.put("b", "http://camunda.org");

    SpinXmlElement child = elementWithNamespace.xPath("/root/a:child/b:a")
      .ns(namespaces)
      .element();

    assertThat(child.name()).isEqualTo("a");
    assertThat(child.namespace()).isEqualTo("http://camunda.org");
    assertThat(child.attr("id").value()).isEqualTo("a");
  }

  @Test
  public void canQueryElementWithDefaultNamespace() {
    SpinXmlElement child = elementWithDefaultNamespace.xPath("/:root/a:child")
      .ns("a", "http://camunda.com")
      .element();

    assertThat(child.name()).isEqualTo("child");
    assertThat(child.namespace()).isEqualTo("http://camunda.com");
    assertThat(child.attr("id").value()).isEqualTo("child");
  }
}