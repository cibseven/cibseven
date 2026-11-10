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
package org.cibseven.bpm.client.variable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.cibseven.bpm.client.spi.DataFormat;
import org.cibseven.bpm.client.variable.impl.format.json.JacksonJsonDataFormatProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Type names ({@code objectTypeName}) of lists stored as JSON object variables (CIB7-2632).
 */
public class ListTypeNameTest {

  public static class Item {

    private String sku;

    public Item() {
    }

    public Item(String sku) {
      this.sku = sku;
    }

    public String getSku() {
      return sku;
    }

    public void setSku(String sku) {
      this.sku = sku;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Item && Objects.equals(sku, ((Item) o).sku);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(sku);
    }
  }

  protected static final String ITEM = Item.class.getName();

  /** A list subclass that fixes its element type, so the type name needs no element detection. */
  public static class ItemList extends ArrayList<Item> {
    private static final long serialVersionUID = 1L;
  }

  protected DataFormat dataFormat;

  @BeforeEach
  public void setUp() {
    dataFormat = new JacksonJsonDataFormatProvider().createInstance();
  }

  @Test
  public void shouldTakeElementTypeFromFirstElement() {
    assertThat(dataFormat.getCanonicalTypeName(new ArrayList<>(List.of(new Item("a")))))
        .isEqualTo("java.util.ArrayList<" + ITEM + ">");
    assertThat(dataFormat.getCanonicalTypeName(new ArrayList<>()))
        .isEqualTo("java.util.ArrayList<java.lang.Object>");
  }

  @Test
  public void shouldSkipNullElements() {
    assertThat(dataFormat.getCanonicalTypeName(new ArrayList<>(Arrays.asList(null, new Item("a")))))
        .isEqualTo("java.util.ArrayList<" + ITEM + ">");
    assertThat(dataFormat.getCanonicalTypeName(new LinkedList<>(Arrays.asList(null, null, "x"))))
        .isEqualTo("java.util.LinkedList<java.lang.String>");
  }

  @Test
  public void shouldUseObjectForListsOfNulls() {
    assertThat(dataFormat.getCanonicalTypeName(new ArrayList<>(Arrays.asList((Object) null))))
        .isEqualTo("java.util.ArrayList<java.lang.Object>");
  }

  @Test
  public void shouldSkipNullElementsInNestedLists() {
    List<Object> nested = new ArrayList<>();
    nested.add(null);
    nested.add(new ArrayList<>(Arrays.asList(null, "x")));

    assertThat(dataFormat.getCanonicalTypeName(nested))
        .isEqualTo("java.util.ArrayList<java.util.ArrayList<java.lang.String>>");
  }

  @Test
  public void shouldReadListWithNullFirstElement() {
    // given
    List<Item> items = new ArrayList<>(Arrays.asList(null, new Item("a")));
    String json = dataFormat.writeValue(items);
    String typeName = dataFormat.getCanonicalTypeName(items);

    // when
    List<Object> read = dataFormat.readValue(json, typeName);

    // then
    assertThat(json).isEqualTo("[null,{\"sku\":\"a\"}]");
    assertThat(read).containsExactly(null, new Item("a"));
  }

  @Test
  public void shouldNameListSubclassWithFixedElementTypeAndNullFirst() {
    // given
    ItemList items = new ItemList();
    items.add(null);
    items.add(new Item("a"));

    // when
    String typeName = dataFormat.getCanonicalTypeName(items);
    ItemList read = dataFormat.readValue(dataFormat.writeValue(items), typeName);

    // then
    assertThat(typeName).isEqualTo(ItemList.class.getName());
    assertThat(read).containsExactly(null, new Item("a"));
  }
}
