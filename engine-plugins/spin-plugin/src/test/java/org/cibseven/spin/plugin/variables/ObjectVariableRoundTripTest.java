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

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.variable.Variables;
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

/**
 * What an object variable reads back as, stored through the engine in its default format, per
 * default serialization format and Spin type detector (CIB7-2803).
 *
 * <p>Every value reads back as written, except the {@link #KNOWN_PROBLEMS}. They are today's
 * behaviour, not the wanted one: a fix that changes one fails this test, and then its entry goes.
 * The same entries were measured with CIB seven 2.1.0 and 2.2.4, Camunda 7.24.0 and Operaton
 * 2.2.1. Unlike 3.0, those could not write a {@code LocalDate} as JSON, nor a list that starts
 * with null (CIB7-2632; a mixed one still cannot be read).
 * <ul>
 * <li>Java serialization keeps every value.</li>
 * <li>JSON without type information inside a map: whole numbers come back as Integer or Long by
 * their size, decimals as Double, dates as epoch milliseconds.</li>
 * <li>A list is typed after its first element (the default list type detector): a list of
 * mixed types fails or changes its elements.</li>
 * <li>The deserialization allow list refuses classes outside java.lang and a few java.util
 * collections, among them BigDecimal, java.time and immutable collections.</li>
 * <li>The map type detector types a map after its first value: a map of mixed values fails or
 * comes back with every value as that type, and a TreeMap comes back as a LinkedHashMap. The set
 * type detector does the same for sets.</li>
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
    knownProblem(Setup.JSON_MAP_DETECTOR, "map Boolean,String", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map Integer,String", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "HashMap a=1,b=x", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "TreeMap of Strings",
        "reads {a=x, b=y} as LinkedHashMap{a:String,b:String,}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "TreeMap a=1,b=x", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "nested map", "read fails");
    knownProblem(Setup.JSON, "map Long 7", "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON_VALIDATION, "map Long 7", "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON_SET_DETECTOR, "map Long 7", "reads {id=7} as LinkedHashMap{id:Integer,}");
    knownProblem(Setup.JSON, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON_VALIDATION, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:BigDecimal,}");
    knownProblem(Setup.JSON_SET_DETECTOR, "map BigDecimal 12.50",
        "reads {amount=12.5} as LinkedHashMap{amount:Double,}");
    knownProblem(Setup.JSON, "map Date", "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON_VALIDATION, "map Date",
        "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON_SET_DETECTOR, "map Date",
        "reads {at=1700000000000} as LinkedHashMap{at:Long,}");
    knownProblem(Setup.JSON, "list [1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [1,\"a\"]", "read fails");
    knownProblem(Setup.JSON, "list [\"a\",1]", "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "list [\"a\",1]", "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [\"a\",1]",
        "reads [a, 1] as ArrayList[String,String,]");
    knownProblem(Setup.JSON, "list [1,5000000000L]", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [1,5000000000L]", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [1,5000000000L]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [1,5000000000L]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "HashSet {1,\"a\"}", "read fails");
    knownProblem(Setup.JSON, "Map.of(\"a\",1)",
        "reads {a=1} as Collections$UnmodifiableMap{a:Integer,}");
    knownProblem(Setup.JSON_VALIDATION, "Map.of(\"a\",1)", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "Map.of(\"a\",1)",
        "reads {a=1} as LinkedHashMap{a:Integer,}");
    knownProblem(Setup.JSON_SET_DETECTOR, "Map.of(\"a\",1)",
        "reads {a=1} as Collections$UnmodifiableMap{a:Integer,}");
    knownProblem(Setup.JSON, "List.of(1,2)", "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_VALIDATION, "List.of(1,2)", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "List.of(1,2)",
        "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_SET_DETECTOR, "List.of(1,2)",
        "reads [1, 2] as ArrayList[Integer,Integer,]");
    knownProblem(Setup.JSON_VALIDATION, "unmodifiableMap mixed", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "unmodifiableMap mixed", "read fails");
    knownProblem(Setup.JSON, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_MAP_DETECTOR, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_SET_DETECTOR, "Arrays.asList(\"a\",\"b\")",
        "reads [a, b] as ArrayList[String,String,]");
    knownProblem(Setup.JSON_VALIDATION, "enum DayOfWeek", "read fails");
    knownProblem(Setup.JSON, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_VALIDATION, "BigDecimal 12.50", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_SET_DETECTOR, "BigDecimal 12.50", "reads 12.5 as BigDecimal");
    knownProblem(Setup.JSON_VALIDATION, "LocalDate", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [Dog,Dog]", "read fails");
    knownProblem(Setup.JSON, "list [Dog,Cat]", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [Dog,Cat]", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [Dog,Cat]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [Dog,Cat]", "read fails");
    knownProblem(Setup.JSON, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON_VALIDATION, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON_SET_DETECTOR, "map of Dogs",
        "reads {a={name=a, barks=true}, b={name=b, barks=false}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,barks:Boolean,},}");
    knownProblem(Setup.JSON, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON_VALIDATION, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON_MAP_DETECTOR, "map of Dog,Cat", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "map of Dog,Cat",
        "reads {a={name=a, barks=true}, b={name=b, lives=7}} as LinkedHashMap{a:LinkedHashMap{name:String,barks:Boolean,},b:LinkedHashMap{name:String,lives:Integer,},}");
    knownProblem(Setup.JSON, "list [null,1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [null,1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [null,1,\"a\"]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [null,1,\"a\"]", "read fails");
    knownProblem(Setup.JSON, "list [[1],[\"a\"]]", "read fails");
    knownProblem(Setup.JSON_VALIDATION, "list [[1],[\"a\"]]", "read fails");
    knownProblem(Setup.JSON_MAP_DETECTOR, "list [[1],[\"a\"]]", "read fails");
    knownProblem(Setup.JSON_SET_DETECTOR, "list [[1],[\"a\"]]", "read fails");
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

  @ParameterizedTest
  @EnumSource(Setup.class)
  void readsBack(Setup setup) {
    Map<String, String> outcomes = run(setup);
    SoftAssertions softly = new SoftAssertions();
    for (Map.Entry<String, String> outcome : outcomes.entrySet()) {
      softly.assertThat(outcome.getValue()).as("%s, %s", setup, outcome.getKey())
          .isEqualTo(KNOWN_PROBLEMS.getOrDefault(setup + "|" + outcome.getKey(), "same"));
    }
    softly.assertAll();
  }

  static Map<String, String> run(Setup setup) {
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
    Map<String, String> outcomes = new LinkedHashMap<>();
    try {
      engine.getRepositoryService().createDeployment()
          .addModelInstance("roundtrip.bpmn",
              Bpmn.createExecutableProcess("roundtrip").startEvent().userTask().endEvent().done())
          .deploy();
      String instance = engine.getRuntimeService().startProcessInstanceByKey("roundtrip").getId();
      int index = 0;
      for (Map.Entry<String, Supplier<Object>> value : values().entrySet()) {
        String name = "value" + index++;
        Object written = value.getValue().get();
        try {
          engine.getRuntimeService().setVariable(instance, name, written);
        } catch (RuntimeException e) {
          outcomes.put(value.getKey(), "write fails");
          continue;
        }
        Object read;
        try {
          read = engine.getRuntimeService().getVariable(instance, name);
        } catch (RuntimeException e) {
          outcomes.put(value.getKey(), "read fails");
          continue;
        }
        outcomes.put(value.getKey(), compare(written, read));
      }
    } finally {
      engine.close();
    }
    return outcomes;
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
    String name = value.getClass().getName().replace("java.util.", "").replace("java.lang.", "")
        .replace("java.math.", "").replace("java.time.", "")
        .replace(ObjectVariableRoundTripTest.class.getName() + "$", "");
    if (value instanceof Map) {
      StringBuilder shape = new StringBuilder(name).append('{');
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        shape.append(entry.getKey()).append(':').append(shape(entry.getValue())).append(',');
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
}
