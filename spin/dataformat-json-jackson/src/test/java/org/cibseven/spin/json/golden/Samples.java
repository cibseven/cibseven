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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic sample values shared by the Jackson 2 writer and the Jackson 3 reader. */
public final class Samples {

  /** Cases written by released Spin 2.2.0 on Jackson 2 (golden fixtures) and read back by the current Spin. */
  public static final List<String> WRITE_CASES = List.of("customer", "itemList", "map", "shapes", "times");

  /** Hand-written inputs, read by both (behaviour comparison). */
  public static final Map<String, String> READ_INPUTS = new LinkedHashMap<>();
  public static final Map<String, Class<?>> READ_TYPES = new LinkedHashMap<>();

  static {
    READ_INPUTS.put("unknownProperty", "{\"sku\":\"A-1\",\"qty\":2,\"extra\":true}");
    READ_TYPES.put("unknownProperty", Item.class);
    READ_INPUTS.put("nullPrimitive", "{\"sku\":\"A-1\",\"qty\":null}");
    READ_TYPES.put("nullPrimitive", Item.class);
    READ_INPUTS.put("enumByName", "{\"name\":\"n\",\"age\":1,\"status\":\"ON_HOLD\",\"active\":false}");
    READ_TYPES.put("enumByName", Customer.class);
    READ_INPUTS.put("dateAsIsoString", "{\"name\":\"n\",\"age\":1,\"createdAt\":\"2026-10-20T11:30:00.000+00:00\",\"active\":false}");
    READ_TYPES.put("dateAsIsoString", Customer.class);
    READ_INPUTS.put("trailingTokens", "{\"sku\":\"A-1\",\"qty\":2} {\"sku\":\"B\"}");
    READ_TYPES.put("trailingTokens", Item.class);
  }

  private Samples() {
  }

  public static Object sample(String name) {
    switch (name) {
      case "customer":
        return customer();
      case "itemList": {
        ArrayList<Item> l = new ArrayList<>();
        l.add(new Item("A-1", 2));
        l.add(new Item("B-7", 1));
        return l;
      }
      case "map": {
        HashMap<String, Object> m = new HashMap<>();
        m.put("count", 3);
        m.put("label", "x");
        m.put("nested", new ArrayList<>(List.of("a", "b")));
        return m;
      }
      case "shapes": {
        ShapesHolder h = new ShapesHolder();
        h.setShapes(new ArrayList<>(List.of(new Circle(1.5), new Square(2))));
        return h;
      }
      case "times": {
        TimeHolder t = new TimeHolder();
        t.setDay(LocalDate.of(2026, 10, 20));
        t.setAt(LocalDateTime.of(2026, 10, 20, 13, 30, 15));
        t.setInstant(Instant.parse("2026-10-20T11:30:00Z"));
        t.setDuration(Duration.ofSeconds(90));
        return t;
      }
      default:
        throw new IllegalArgumentException(name);
    }
  }

  public static Customer customer() {
    Customer c = new Customer();
    c.setName("BMW Financial Services test");
    c.setAge(42);
    c.setCreatedAt(new Date(1792495800000L)); // 2026-10-20T11:30:00Z
    c.setStatus(Status.ON_HOLD);
    c.setItems(new ArrayList<>(List.of(new Item("A-1", 2), new Item("B-7", 1))));
    LinkedHashMap<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("zeta", "last");
    attrs.put("alpha", 1);
    c.setAttributes(attrs);
    c.setBalance(new BigDecimal("1234.50"));
    c.setActive(true);
    return c;
  }
}
