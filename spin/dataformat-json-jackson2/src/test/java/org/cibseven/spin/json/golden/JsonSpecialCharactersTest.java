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
package org.cibseven.spin.json.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.io.StringWriter;

import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.junit.jupiter.api.Test;

/**
 * Pins how the Spin 2.x code on Jackson 2 writes special characters. JsonGoldenFilesTest of
 * cibseven-spin-dataformat-json-jackson (Jackson 3) expects exactly the same text.
 */
public class JsonSpecialCharactersTest {

  /** Emoji (surrogate pair), euro sign, e acute, slash, control character U+0001, tab, quote, lone surrogate. */
  static final String SPECIAL_CHARACTERS =
      "smile \uD83D\uDE00 euro \u20AC \u00E9 slash / ctrl \u0001 tab \t quote \" lone \uD800 end";
  /** As written by Spin on Jackson 2 and Jackson 3: only the control characters and the quote are escaped. */
  static final String SPECIAL_CHARACTERS_JSON =
      "{\"sku\":\"smile \uD83D\uDE00 euro \u20AC \u00E9 slash / ctrl \\u0001 tab \\t quote \\\" lone \uD800 end\",\"qty\":1}";
  /** The same value with every non-ASCII character escaped in the input. */
  static final String SPECIAL_CHARACTERS_ESCAPED_JSON =
      "{\"sku\":\"smile \\uD83D\\uDE00 euro \\u20AC \\u00E9 slash \\/ ctrl \\u0001 tab \\t quote \\\" lone \\uD800 end\",\"qty\":1}";

  protected final JacksonJsonDataFormat format = new JacksonJsonDataFormat("application/json");

  @Test
  public void shouldWriteSpecialCharacters() {
    Item item = new Item();
    item.setSku(SPECIAL_CHARACTERS);
    item.setQty(1);

    String json = write(format.getMapper().mapJavaToInternal(item));
    String rewritten = write(format.getReader().readInput(new StringReader(json)));
    String rewrittenFromEscapes = write(format.getReader().readInput(new StringReader(SPECIAL_CHARACTERS_ESCAPED_JSON)));

    assertThat(json).isEqualTo(SPECIAL_CHARACTERS_JSON);
    assertThat(rewritten).isEqualTo(SPECIAL_CHARACTERS_JSON);
    assertThat(rewrittenFromEscapes).isEqualTo(SPECIAL_CHARACTERS_JSON);
  }

  protected String write(Object internal) {
    StringWriter writer = new StringWriter();
    format.getWriter().writeToWriter(writer, internal);
    return writer.toString();
  }

  public static class Item {
    private String sku;
    private int qty;

    public String getSku() {
      return sku;
    }

    public void setSku(String sku) {
      this.sku = sku;
    }

    public int getQty() {
      return qty;
    }

    public void setQty(int qty) {
      this.qty = qty;
    }
  }
}
