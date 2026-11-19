/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
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
package org.cibseven.bpm.client.variable.impl.format.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cibseven.bpm.client.exception.DataFormatException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * Regression test for CVE-2026-77224 (CIB7-2847): the external task client must not
 * resolve external entities when parsing application/xml object variables.
 */
public class DomXmlDataFormatXxeTest {

  protected final DomXmlDataFormat format = new DomXmlDataFormat("application/xml");

  @Test
  public void shouldParseRegularXml() {
    Element element = format.readAsElement("<foo>bar</foo>");

    assertThat(element.getTextContent()).isEqualTo("bar");
  }

  @Test
  public void shouldRejectDoctypeDeclaration() {
    String xml = "<?xml version=\"1.0\"?>\n"
        + "<!DOCTYPE foo [ <!ENTITY x \"expanded\"> ]>\n"
        + "<foo>&x;</foo>";

    assertThatThrownBy(() -> format.readAsElement(xml))
        .isInstanceOf(DataFormatException.class);
  }

  @Test
  public void shouldNotResolveExternalEntity() throws IOException {
    Path secret = Files.createTempFile("xxe-regression", ".txt");
    Files.write(secret, "TOP-SECRET".getBytes());

    String xml = "<?xml version=\"1.0\"?>\n"
        + "<!DOCTYPE foo [ <!ENTITY x SYSTEM \"" + secret.toUri() + "\"> ]>\n"
        + "<foo>&x;</foo>";

    // The DOCTYPE is rejected outright, so the file content can never reach the document.
    assertThatThrownBy(() -> format.readAsElement(xml))
        .isInstanceOf(DataFormatException.class);

    Files.deleteIfExists(secret);
  }
}
