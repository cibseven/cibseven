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
package org.cibseven.spin.plugin.variables;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.cibseven.bpm.engine.variable.value.TypedValue;
import org.cibseven.bpm.model.bpmn.Bpmn;
import org.cibseven.spin.DataFormats;
import org.cibseven.spin.impl.json.jackson.format.JacksonJsonDataFormat;
import org.cibseven.spin.impl.json.jackson.format.MapJacksonJsonTypeDetector;
import org.cibseven.spin.impl.json.jackson.format.SetJacksonJsonTypeDetector;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.cibseven.spin.spi.DataFormatConfigurator;
import org.cibseven.spin.spi.TypeDetector;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.core.JacksonException;

/**
 * What an object variable reads back as, stored through the engine in its default format, per
 * default serialization format and Spin type detector (CIB7-2803).
 *
 * <p>Every value reads back as written, except the {@link #KNOWN_PROBLEMS}. They are today's
 * behaviour, not the wanted one: a fix that changes one fails this test, and then its entry goes.
 * The same entries were measured with CIB seven 2.1.0 and 2.2.4, Camunda 7.24.0 and Operaton
 * 2.2.1. Unlike 3.0, those could not write a {@code LocalDate} as JSON, nor a list that starts
 * with null (CIB7-2632; a mixed one still cannot be read). That comparison did not cover what
 * came later: the setup {@code JSON_MAP_DETECTOR_VALIDATION}, the value {@code HashMap 1=a} and
 * the causes of failed reads.
 *
 * <p>Each object value is stored in the default format of its setup, under the type name in
 * {@code ObjectVariableRoundTripTest-stored.txt}.
 * <ul>
 * <li>Java serialization keeps every value.</li>
 * <li>JSON without type information inside a map: whole numbers come back as Integer or Long by
 * their size, decimals as Double, dates as epoch milliseconds. A key that is not a String comes
 * back as a String, except with the map type detector.</li>
 * <li>A list is typed after its first element (the default list type detector): a list of
 * mixed types fails or changes its elements.</li>
 * <li>The deserialization allow list refuses classes outside java.lang and a few java.util
 * collections, among them BigDecimal, java.time and immutable collections.</li>
 * <li>The map type detector types a map after its first value: a map of mixed values fails or
 * comes back with every value as that type, and a TreeMap comes back as a LinkedHashMap. The set
 * type detector does the same for sets. With type validation as well, no map reads back: the map
 * type detector stores the type java.util.Map, which the allow list refuses.</li>
 * </ul>
 */
public class ObjectVariableRoundTripTest {

  /** How the engine is set up. */
  enum Setup {
    JAVA(false, false, null),
    JAVA_VALIDATION(false, true, null),
    JSON(true, false, null),
    JSON_VALIDATION(true, true, null),
    JSON_MAP_DETECTOR(true, false, MapJacksonJsonTypeDetector.INSTANCE),
    JSON_MAP_DETECTOR_VALIDATION(true, true, MapJacksonJsonTypeDetector.INSTANCE),
    JSON_SET_DETECTOR(true, false, SetJacksonJsonTypeDetector.INSTANCE);

    final boolean json;
    final boolean validation;
    final TypeDetector detector;

    Setup(boolean json, boolean validation, TypeDetector detector) {
      this.json = json;
      this.validation = validation;
      this.detector = detector;
    }
  }

  /** The values stored, by name. */
  static Map<String, Supplier<Object>> values() {
    Map<String, Supplier<Object>> values = new LinkedHashMap<>();
    values.put("map String,Boolean,Integer", () -> map("name", "x", "flag", true, "count", 5));
    values.put("map Boolean,String", () -> map("aiGenerated", true, "runId", "r-1"));
    values.put("map Integer,String", () -> map("count", 5, "name", "x"));
    values.put("HashMap a=1,b=x", () -> new HashMap<>(map("a", 1, "b", "x")));
    values.put("HashMap 1=a", () -> new HashMap<>(Map.of(1, "a")));
    values.put("TreeMap of Strings", () -> new TreeMap<>(map("a", "x", "b", "y")));
    values.put("TreeMap a=1,b=x", () -> new TreeMap<>(map("a", 1, "b", "x")));
    values.put("nested map", () -> map("inner", map("a", 1), "name", "x"));
    values.put("map Long 7", () -> map("id", 7L));
    values.put("map Long 5000000000", () -> map("id", 5_000_000_000L));
    values.put("map BigDecimal 12.50", () -> map("amount", new BigDecimal("12.50")));
    values.put("map Double 1.5", () -> map("d", 1.5d));
    values.put("map Date", () -> map("at", new Date(1_700_000_000_000L)));
    values.put("list [1,\"a\"]", () -> new ArrayList<>(Arrays.asList(1, "a")));
    values.put("list [\"a\",1]", () -> new ArrayList<>(Arrays.asList("a", 1)));
    values.put("list [1,5000000000L]", () -> new ArrayList<>(Arrays.asList(1, 5_000_000_000L)));
    values.put("list [7L]", () -> new ArrayList<>(Arrays.asList(7L)));
    values.put("list of mixed maps", () -> new ArrayList<>(Arrays.asList(map("a", 1, "b", "x"))));
    values.put("HashSet of Strings", () -> new HashSet<>(Arrays.asList("a", "b")));
    values.put("HashSet {1,\"a\"}", () -> new HashSet<>(Arrays.asList(1, "a")));
    values.put("Map.of(\"a\",1)", () -> Map.of("a", 1));
    values.put("List.of(1,2)", () -> List.of(1, 2));
    values.put("unmodifiableMap mixed", () -> Collections.unmodifiableMap(map("a", true, "b", "x")));
    values.put("Arrays.asList(\"a\",\"b\")", () -> Arrays.asList("a", "b"));
    values.put("enum DayOfWeek", () -> DayOfWeek.MONDAY);
    values.put("BigDecimal 12.50", () -> new BigDecimal("12.50"));
    values.put("Date", () -> new Date(1_700_000_000_000L));
    values.put("LocalDate", () -> LocalDate.of(2026, 11, 16));
    values.put("list [Dog,Dog]",
        () -> new ArrayList<>(Arrays.asList(new Dog("a", true), new Dog("b", false))));
    values.put("list [Dog,Cat]",
        () -> new ArrayList<>(Arrays.asList(new Dog("a", true), new Cat("b", 7))));
    values.put("map of Dogs", () -> map("a", new Dog("a", true), "b", new Dog("b", false)));
    values.put("map of Dog,Cat", () -> map("a", new Dog("a", true), "b", new Cat("b", 7)));
    values.put("map null,String", () -> map("a", null, "b", "x"));
    values.put("list [null,1,\"a\"]", () -> new ArrayList<>(Arrays.asList(null, 1, "a")));
    values.put("list [[1,2],[3]]", () -> new ArrayList<>(Arrays.asList(
        new ArrayList<>(Arrays.asList(1, 2)), new ArrayList<>(Arrays.asList(3)))));
    values.put("list [[1],[\"a\"]]", () -> new ArrayList<>(Arrays.asList(
        new ArrayList<>(Arrays.asList(1)), new ArrayList<>(Arrays.asList("a")))));
    return values;
  }

  /** A value class with a subclass of its own each, as applications store them. */
  public static class Animal implements Serializable {
    private static final long serialVersionUID = 1L;
    public String name;

    public Animal() {
    }

    Animal(String name) {
      this.name = name;
    }

    @Override
    public boolean equals(Object other) {
      return other != null && other.getClass() == getClass()
          && Objects.equals(name, ((Animal) other).name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(name);
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + name + ")";
    }
  }

  public static class Dog extends Animal {
    private static final long serialVersionUID = 1L;
    public boolean barks;

    public Dog() {
    }

    Dog(String name, boolean barks) {
      super(name);
      this.barks = barks;
    }

    @Override
    public boolean equals(Object other) {
      return super.equals(other) && barks == ((Dog) other).barks;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, barks);
    }
  }

  public static class Cat extends Animal {
    private static final long serialVersionUID = 1L;
    public int lives;

    public Cat() {
    }

    Cat(String name, int lives) {
      super(name);
      this.lives = lives;
    }

    @Override
    public boolean equals(Object other) {
      return super.equals(other) && lives == ((Cat) other).lives;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, lives);
    }
  }

  static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  /** What does not read back as written today, by setup and value; see the class comment. */
  static final Map<String, String> KNOWN_PROBLEMS = new LinkedHashMap<>();

  static {
    knownProblem(Setup.JSON_MAP_DETECTOR, "map String,Boolean,Integer",
        "reads {name=x, flag=true, count=5} as LinkedHashMap{name:String,flag:String,count:String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map String,Boolean,Integer",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map Boolean,String", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Boolean,String",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map Integer,String", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Integer,String",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "HashMap a=1,b=x", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "HashMap a=1,b=x",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "HashMap 1=a", "reads {1=a} as HashMap{1:String,}");
    knownProblem(Setup.JSON_VALIDATION, "HashMap 1=a", "reads {1=a} as HashMap{1:String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "HashMap 1=a",
        "reads {1=a} as LinkedHashMap{1(Integer):String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "HashMap 1=a",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_SET_DETECTOR, "HashMap 1=a", "reads {1=a} as HashMap{1:String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "TreeMap of Strings",
        "reads {a=x, b=y} as LinkedHashMap{a:String,b:String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "TreeMap of Strings",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "TreeMap a=1,b=x", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "TreeMap a=1,b=x",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "nested map", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "nested map", "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "map Long 7", "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON_VALIDATION, "map Long 7", "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Long 7", "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_SET_DETECTOR, "map Long 7",
        "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Long 5000000000",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON_VALIDATION, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:BigDecimal,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map BigDecimal 12.50",
        "read fails: not allowed [Map, BigDecimal]");
    knownProblem(Setup.JSON_SET_DETECTOR, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Double 1.5",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "map Date", "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON_VALIDATION, "map Date",
        "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map Date",
        "read fails: not allowed [Map, Date]");
    knownProblem(Setup.JSON_SET_DETECTOR, "map Date",
        "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON, "list [1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_VALIDATION, "list [1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON, "list [\"a\",1]", "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON, "list [1,5000000000L]", "read fails: cannot map");
    knownProblem(Setup.JSON_VALIDATION, "list [1,5000000000L]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [1,5000000000L]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [1,5000000000L]",
        "read fails: cannot map");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [1,5000000000L]", "read fails: cannot map");
    knownProblem(Setup.JSON_SET_DETECTOR, "HashSet {1,\"a\"}", "read fails: cannot map");
    knownProblem(Setup.JSON, "Map.of(\"a\",1)",
        "reads {a=1} as Collections$UnmodifiableMap{a:Integer,}");
    knownProblem(Setup.JSON_VALIDATION, "Map.of(\"a\",1)",
        "read fails: not allowed [ImmutableCollections$Map1]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "Map.of(\"a\",1)",
        "reads {a=1} as LinkedHashMap{a:Integer,}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "Map.of(\"a\",1)",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON_SET_DETECTOR, "Map.of(\"a\",1)",
        "reads {a=1} as Collections$UnmodifiableMap{a:Integer,}");
    knownProblem(Setup.JSON, "List.of(1,2)", "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_VALIDATION, "List.of(1,2)",
        "read fails: not allowed [ImmutableCollections$List12]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "List.of(1,2)",
        "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "List.of(1,2)",
        "read fails: not allowed [ImmutableCollections$List12]");
    knownProblem(Setup.JSON_SET_DETECTOR, "List.of(1,2)",
        "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_VALIDATION, "unmodifiableMap mixed",
        "read fails: not allowed [Collections$UnmodifiableMap]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "unmodifiableMap mixed", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "unmodifiableMap mixed",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_SET_DETECTOR, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "enum DayOfWeek", "read fails: not allowed [DayOfWeek]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "enum DayOfWeek",
        "read fails: not allowed [DayOfWeek]");
    knownProblem(Setup.JSON, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_VALIDATION, "BigDecimal 12.50", "read fails: not allowed [BigDecimal]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "BigDecimal 12.50",
        "read fails: not allowed [BigDecimal]");
    knownProblem(Setup.JSON_SET_DETECTOR, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_VALIDATION, "LocalDate", "read fails: not allowed [LocalDate]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "LocalDate",
        "read fails: not allowed [LocalDate]");
    knownProblem(Setup.JSON_VALIDATION, "list [Dog,Dog]", "read fails: not allowed [Dog]");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [Dog,Dog]",
        "read fails: not allowed [Dog]");
    knownProblem(Setup.JSON, "list [Dog,Cat]", "read fails: cannot map");
    knownProblem(Setup.JSON_VALIDATION, "list [Dog,Cat]", "read fails: not allowed [Dog]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [Dog,Cat]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [Dog,Cat]",
        "read fails: not allowed [Dog]");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [Dog,Cat]", "read fails: cannot map");
    knownProblem(Setup.JSON, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON_VALIDATION, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map of Dogs",
        "read fails: not allowed [Map, Dog]");
    knownProblem(Setup.JSON_SET_DETECTOR, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON_VALIDATION, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map of Dog,Cat", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map of Dog,Cat",
        "read fails: not allowed [Map, Dog]");
    knownProblem(Setup.JSON_SET_DETECTOR, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "map null,String",
        "read fails: not allowed [Map]");
    knownProblem(Setup.JSON, "list [null,1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_VALIDATION, "list [null,1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [null,1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [null,1,\"a\"]",
        "read fails: cannot map");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [null,1,\"a\"]", "read fails: cannot map");
    knownProblem(Setup.JSON, "list [[1],[\"a\"]]", "read fails: cannot map");
    knownProblem(Setup.JSON_VALIDATION, "list [[1],[\"a\"]]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [[1],[\"a\"]]", "read fails: cannot map");
    knownProblem(Setup.JSON_MAP_DETECTOR_VALIDATION, "list [[1],[\"a\"]]",
        "read fails: cannot map");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [[1],[\"a\"]]", "read fails: cannot map");
  }

  static void knownProblem(Setup setup, String value, String outcome) {
    KNOWN_PROBLEMS.put(setup + "|" + value, outcome);
  }

  /** Spin with one more type detector, as an application adds it through a configurator. */
  static class SpinWithTypeDetector extends SpinProcessEnginePlugin {

    private final TypeDetector detector;

    SpinWithTypeDetector(TypeDetector detector) {
      this.detector = detector;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
      DataFormats.loadDataFormats(SpinProcessEnginePlugin.class.getClassLoader(),
          Collections.<DataFormatConfigurator>singletonList(
              new DataFormatConfigurator<JacksonJsonDataFormat>() {

                @Override
                public Class<JacksonJsonDataFormat> getDataFormatClass() {
                  return JacksonJsonDataFormat.class;
                }

                @Override
                public void configure(JacksonJsonDataFormat dataFormat) {
                  dataFormat.addTypeDetector(detector);
                }
              }));
    }
  }

  @AfterAll
  static void reloadDataFormats() {
    // Spin's data formats are global: drop the detector for whatever runs next.
    DataFormats.loadDataFormats(SpinProcessEnginePlugin.class.getClassLoader());
  }

  /** What each setup read back and stored, by value; each engine runs once for both tests. */
  static final Map<Setup, Outcomes> OUTCOMES = new EnumMap<>(Setup.class);

  static class Outcomes {
    /** "same", how the value read back differs, or why writing or reading it failed. */
    final Map<String, String> read = new LinkedHashMap<>();
    /** How the value is stored, see {@link #stored(TypedValue)}. */
    final Map<String, String> stored = new LinkedHashMap<>();
  }

  static Outcomes outcomes(Setup setup) {
    return OUTCOMES.computeIfAbsent(setup, ObjectVariableRoundTripTest::run);
  }

  @ParameterizedTest
  @EnumSource(Setup.class)
  void readsBack(Setup setup) {
    SoftAssertions softly = new SoftAssertions();
    for (Map.Entry<String, String> outcome : outcomes(setup).read.entrySet()) {
      softly.assertThat(outcome.getValue()).as("%s, %s", setup, outcome.getKey())
          .isEqualTo(KNOWN_PROBLEMS.getOrDefault(setup + "|" + outcome.getKey(), "same"));
    }
    softly.assertAll();
  }

  /**
   * Every object value is stored in the setup's default format, under the type name in
   * {@code ObjectVariableRoundTripTest-stored.txt}. A fix that changes a type name changes what
   * is in the database: update that file on purpose.
   */
  @ParameterizedTest
  @EnumSource(Setup.class)
  void storesInTheDefaultFormat(Setup setup) {
    String format = setup.json ? "json " : "java ";
    List<String> stored = new ArrayList<>();
    SoftAssertions softly = new SoftAssertions();
    for (Map.Entry<String, String> value : outcomes(setup).stored.entrySet()) {
      String how = value.getValue();
      if (how.startsWith("json ") || how.startsWith("java ")) {
        softly.assertThat(how).as("%s, %s", setup, value.getKey()).startsWith(format);
      }
      stored.add(setup + "|" + value.getKey() + "|" + how);
    }
    softly.assertThat(stored).as("%s, stored", setup)
        .containsExactlyElementsOf(expectedStored(setup));
    softly.assertAll();
  }

  static List<String> expectedStored(Setup setup) {
    List<String> lines = new ArrayList<>();
    try (InputStream in = ObjectVariableRoundTripTest.class
        .getResourceAsStream("ObjectVariableRoundTripTest-stored.txt");
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith(setup + "|")) {
          lines.add(line);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return lines;
  }

  static Outcomes run(Setup setup) {
    StandaloneInMemProcessEngineConfiguration configuration =
        new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("roundtrip-" + setup.name());
    configuration.setJdbcUrl("jdbc:h2:mem:roundtrip-" + setup.name() + ";DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P1D");
    if (setup.json) {
      configuration.setDefaultSerializationFormat(Variables.SerializationDataFormats.JSON.getName());
    }
    configuration.setDeserializationTypeValidationEnabled(setup.validation);
    List<ProcessEnginePlugin> plugins = new ArrayList<>();
    plugins.add(setup.detector == null
        ? new SpinProcessEnginePlugin()
        : new SpinWithTypeDetector(setup.detector));
    configuration.setProcessEnginePlugins(plugins);
    ProcessEngine engine = configuration.buildProcessEngine();
    Outcomes outcomes = new Outcomes();
    try {
      engine.getRepositoryService().createDeployment()
          .addModelInstance("roundtrip.bpmn",
              Bpmn.createExecutableProcess("roundtrip").startEvent().userTask().endEvent().done())
          .deploy();
      RuntimeService runtimeService = engine.getRuntimeService();
      String instance = runtimeService.startProcessInstanceByKey("roundtrip").getId();
      int index = 0;
      for (Map.Entry<String, Supplier<Object>> value : values().entrySet()) {
        String name = "value" + index++;
        Object written = value.getValue().get();
        try {
          runtimeService.setVariable(instance, name, written);
        } catch (RuntimeException e) {
          outcomes.read.put(value.getKey(), "write fails: " + cause(e));
          continue;
        }
        outcomes.stored.put(value.getKey(),
            stored(runtimeService.getVariableTyped(instance, name, false)));
        Object read;
        try {
          read = runtimeService.getVariable(instance, name);
        } catch (RuntimeException e) {
          outcomes.read.put(value.getKey(), "read fails: " + cause(e));
          continue;
        }
        outcomes.read.put(value.getKey(), compare(written, read));
      }
    } finally {
      engine.close();
    }
    return outcomes;
  }

  /** "json" or "java" and the object type name, or the type of a value that is not an object. */
  static String stored(TypedValue value) {
    if (value instanceof ObjectValue) {
      ObjectValue object = (ObjectValue) value;
      String format = object.getSerializationDataFormat();
      if (Variables.SerializationDataFormats.JSON.getName().equals(format)) {
        format = "json";
      } else if (Variables.SerializationDataFormats.JAVA.getName().equals(format)) {
        format = "java";
      }
      return format + " " + object.getObjectTypeName();
    }
    return value.getType().getName();
  }

  /** The start of the message with which Spin's type validation refuses classes. */
  static final String NOT_ALLOWED =
      "The following classes are not whitelisted for deserialization: ";

  /**
   * Why writing or reading failed: "not allowed" and the classes the type validation refused,
   * "cannot map" if Jackson could not map the JSON to the stored type, else the exception.
   */
  static String cause(RuntimeException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = String.valueOf(t.getMessage());
      int refused = message.indexOf(NOT_ALLOWED);
      if (refused >= 0) {
        return "not allowed " + simpleNames(message.substring(refused + NOT_ALLOWED.length()));
      }
    }
    Throwable root = e;
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof JacksonException) {
        return "cannot map";
      }
      root = t;
    }
    return root.getClass().getSimpleName();
  }

  static String compare(Object written, Object read) {
    String writtenShape = shape(written);
    String readShape = shape(read);
    if (written.equals(read) && writtenShape.equals(readShape)) {
      return "same";
    }
    return "reads " + read + " as " + readShape;
  }

  /** The classes of a value and of everything it holds. */
  static String shape(Object value) {
    if (value == null) {
      return "null";
    }
    String name = name(value);
    if (value instanceof Map) {
      StringBuilder shape = new StringBuilder(name).append('{');
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        Object key = entry.getKey();
        // a key that is not a String shows its class: 1 and "1" print the same
        shape.append(key instanceof String || key == null ? key : key + "(" + name(key) + ")")
            .append(':').append(shape(entry.getValue())).append(',');
      }
      return shape.append('}').toString();
    }
    if (value instanceof Collection) {
      StringBuilder shape = new StringBuilder(name).append('[');
      for (Object element : (Collection<?>) value) {
        shape.append(shape(element)).append(',');
      }
      return shape.append(']').toString();
    }
    return name;
  }

  static String name(Object value) {
    return simpleNames(value.getClass().getName());
  }

  static String simpleNames(String classNames) {
    return classNames.replace("java.util.", "").replace("java.lang.", "")
        .replace("java.math.", "").replace("java.time.", "")
        .replace(ObjectVariableRoundTripTest.class.getName() + "$", "");
  }
}
