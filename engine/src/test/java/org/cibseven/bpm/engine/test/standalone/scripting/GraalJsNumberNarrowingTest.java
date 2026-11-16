/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.bpm.engine.test.standalone.scripting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.LONG;
import static org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing.NONE;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.scripting.engine.CamundaScriptEngineManager;
import org.cibseven.bpm.engine.impl.scripting.engine.DefaultScriptEngineResolver;
import org.cibseven.bpm.engine.impl.scripting.engine.GraalJsNumberNarrowing;
import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.word.WordBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;

/**
 * CIB7-2665: the script engines of {@code javaScriptNumberNarrowing} with the script engine resolvers of the
 * process engine, without a process engine: the configuration is the one of the command context.
 */
public class GraalJsNumberNarrowingTest {

  /** A Java parameter of type {@code Object}, as {@code VariableScope.setVariable(String, Object)}. */
  public static class Sink {
    protected Object value;

    public void set(Object value) {
      this.value = value;
    }

    /** No mapping applies to a parameter of type {@code Number}: GraalJS chooses the type. */
    public void setNumber(Number value) {
      this.value = value;
    }
  }

  protected static ProcessEngineConfigurationImpl configuration(String mode) {
    return new StandaloneInMemProcessEngineConfiguration().setJavaScriptNumberNarrowing(mode);
  }

  protected static ProcessEngineConfigurationImpl nashornCompatibility(String mode, boolean useCibSevenNamespace) {
    ProcessEngineConfigurationImpl configuration = configuration(mode).setEnableScriptEngineNashornCompatibility(true);
    configuration.setUseCibSevenNamespaceInScripting(useCibSevenNamespace);
    return configuration;
  }

  /** As ProcessApplicationScriptEnvironment: one resolver for every process engine of a process application. */
  protected static DefaultScriptEngineResolver sharedResolver() {
    return new DefaultScriptEngineResolver(new CamundaScriptEngineManager());
  }

  /** @return what the script hands to {@code sink.set}, or else its result */
  protected static Object evaluate(DefaultScriptEngineResolver resolver, ProcessEngineConfigurationImpl configuration,
      boolean cache, String script) throws ScriptException {
    Context.setProcessEngineConfiguration(configuration);
    try {
      ScriptEngine scriptEngine = resolver.getScriptEngine("javascript", cache);
      Sink sink = new Sink();
      scriptEngine.getContext().setAttribute("sink", sink, ScriptContext.ENGINE_SCOPE);
      Object result = scriptEngine.eval(script);
      return sink.value != null ? sink.value : result;
    } finally {
      Context.removeProcessEngineConfiguration();
    }
  }

  /** @return the script engine that the resolver gives for JavaScript with the configuration */
  protected static ScriptEngine resolve(DefaultScriptEngineResolver resolver,
      ProcessEngineConfigurationImpl configuration) {
    Context.setProcessEngineConfiguration(configuration);
    try {
      return resolver.getScriptEngine("javascript", true);
    } finally {
      Context.removeProcessEngineConfiguration();
    }
  }

  static Stream<Arguments> paths() {
    return Stream.of(Arguments.of(false), Arguments.of(true));
  }

  static Stream<Arguments> modesOneAfterTheOther() {
    return Stream.of(
        Arguments.of(LONG, NONE, true),
        Arguments.of(NONE, LONG, true),
        Arguments.of(LONG, NONE, false),
        Arguments.of(NONE, LONG, false));
  }

  static Stream<Arguments> numbers() {
    return Stream.of(
        // what the script hands to sink.set, the value in mode long, the value in mode none
        Arguments.of("java.lang.Long.valueOf(-5)", -5, -5L),
        Arguments.of("java.lang.Long.valueOf(java.lang.Integer.MAX_VALUE)", Integer.MAX_VALUE, (long) Integer.MAX_VALUE),
        Arguments.of("java.lang.Long.valueOf(java.lang.Integer.MIN_VALUE)", Integer.MIN_VALUE, (long) Integer.MIN_VALUE),
        Arguments.of("java.lang.Long.valueOf('2147483648')", 2147483648L, 2147483648L),
        Arguments.of("java.lang.Long.valueOf('-2147483649')", -2147483649L, -2147483649L),
        Arguments.of("java.lang.Long.valueOf('9007199254740993')", 9007199254740993L, 9007199254740993L),
        Arguments.of("java.lang.Long.valueOf('-9007199254740993')", -9007199254740993L, -9007199254740993L),
        Arguments.of("java.lang.Long.MAX_VALUE", Long.MAX_VALUE, Long.MAX_VALUE),
        // GraalJS hands a Short, a Byte or a Float over as a JavaScript number
        Arguments.of("java.lang.Short.valueOf(5)", 5, 5),
        Arguments.of("java.lang.Byte.valueOf(5)", 5, 5),
        Arguments.of("java.lang.Float.valueOf(1.5)", 1.5d, 1.5d),
        Arguments.of("java.lang.Double.valueOf(6)", 6.0d, 6.0d),
        Arguments.of("java.lang.Integer.valueOf(5)", 5, 5),
        Arguments.of("5", 5, 5),
        Arguments.of("-5", -5, -5),
        Arguments.of("2147483648", 2147483648d, 2147483648d),
        Arguments.of("2.5", 2.5d, 2.5d));
  }

  @ParameterizedTest(name = "{0} then {1}, cache {2}")
  @MethodSource("modesOneAfterTheOther")
  public void shouldServeTwoProcessEnginesWithDifferentModesFromOneResolver(String first, String second,
      boolean cache) throws ScriptException {
    // given: a process application on two process engines, one with each mode
    DefaultScriptEngineResolver resolver = sharedResolver();
    String script = "sink.set(java.lang.Long.valueOf(5))";

    // when
    Object fromFirst = evaluate(resolver, configuration(first), cache, script);
    Object fromSecond = evaluate(resolver, configuration(second), cache, script);

    // then: each with the type of its mode; GraalJS would reject a second host access on one polyglot engine
    assertThat(fromFirst).isEqualTo(LONG.equals(first) ? (Object) 5 : (Object) 5L);
    assertThat(fromSecond).isEqualTo(LONG.equals(second) ? (Object) 5 : (Object) 5L);
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldNarrowInTheNashornCompatibilityMode(boolean useCibSevenNamespace, @TempDir Path directory)
      throws ScriptException, IOException {
    // given
    DefaultScriptEngineResolver resolver = sharedResolver();
    ProcessEngineConfigurationImpl configuration = nashornCompatibility(LONG, useCibSevenNamespace);
    Path script = Files.writeString(directory.resolve("loaded.js"), "var loadedValue = 42;");

    // when
    Object longValueOf = evaluate(resolver, configuration, true, "sink.set(java.lang.Long.valueOf(5))");
    // a conversion of Nashorn: a number for a String parameter
    Object parsed = evaluate(resolver, configuration, true, "sink.set(java.lang.Integer.parseInt(42))");
    // the settings of the mode: its globals, and the access to files that GraalJS allows in it
    Object javaImporter = evaluate(resolver, configuration, true, "typeof JavaImporter");
    Object loaded = evaluate(resolver, configuration, true,
        "load('" + script.toAbsolutePath().toString().replace('\\', '/') + "'); loadedValue");

    // then
    assertThat(longValueOf).isEqualTo(5);
    assertThat(parsed).isEqualTo(42);
    assertThat(javaImporter).isEqualTo("function");
    assertThat(loaded).isEqualTo(42);
  }

  @ParameterizedTest(name = "Nashorn compatibility first: {0}")
  @ValueSource(booleans = {true, false})
  public void shouldNarrowWithAndWithoutNashornCompatibilityFromOneResolver(boolean nashornFirst)
      throws ScriptException {
    // given: a process application on two process engines in mode long, one of them in the Nashorn compatibility mode
    DefaultScriptEngineResolver resolver = sharedResolver();
    ProcessEngineConfigurationImpl nashorn = nashornCompatibility(LONG, false);
    ProcessEngineConfigurationImpl plain = configuration(LONG);
    String script = "sink.set(java.lang.Long.valueOf(5))";
    String parse = "sink.set(java.lang.Integer.parseInt(42))";

    // when
    Object fromFirst = evaluate(resolver, nashornFirst ? nashorn : plain, true, script);
    Object fromSecond = evaluate(resolver, nashornFirst ? plain : nashorn, true, script);
    Object parsedWithNashorn = evaluate(resolver, nashorn, true, parse);

    // then: both narrowed, each host access on a polyglot engine of its own (GraalJS rejects a second host access on
    // one), and the conversions of Nashorn only in that mode
    assertThat(fromFirst).isEqualTo(5);
    assertThat(fromSecond).isEqualTo(5);
    assertThat(parsedWithNashorn).isEqualTo(42);
    assertThatThrownBy(() -> evaluate(resolver, plain, true, parse)).isInstanceOf(ScriptException.class);
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldKeepTheTypesOfGraalJsInModeNoneWithNashornCompatibility(boolean useCibSevenNamespace)
      throws ScriptException {
    // given
    DefaultScriptEngineResolver resolver = sharedResolver();
    ProcessEngineConfigurationImpl configuration = nashornCompatibility(NONE, useCibSevenNamespace);

    // when
    Object longValueOf = evaluate(resolver, configuration, true, "sink.set(java.lang.Long.valueOf(5))");
    Object parsed = evaluate(resolver, configuration, true, "sink.set(java.lang.Integer.parseInt(42))");
    Object javaImporter = evaluate(resolver, configuration, true, "typeof JavaImporter");

    // then: the script engine of GraalJS in the Nashorn compatibility mode, as without the narrowing
    assertThat(GraalJsNumberNarrowing.hasOwnHostAccess(resolve(resolver, configuration))).isFalse();
    assertThat(longValueOf).isEqualTo(5L);
    assertThat(parsed).isEqualTo(42);
    assertThat(javaImporter).isEqualTo("function");
  }

  @Test
  public void shouldKeepTheCamundaNamespaceWhenNarrowing() throws ScriptException {
    // given: the script engine of CamundaScriptEngineManager, with its class loader for org.camunda
    DefaultScriptEngineResolver resolver = sharedResolver();
    ProcessEngineConfigurationImpl configuration = configuration(LONG);
    configuration.setUseCibSevenNamespaceInScripting(true);

    // when
    Object className = evaluate(resolver, configuration, true,
        "Java.type('org.camunda.bpm.engine.variable.Variables').class.getName()");
    Object longValueOf = evaluate(resolver, configuration, true, "sink.set(java.lang.Long.valueOf(5))");

    // then
    assertThat(className).isEqualTo("org.cibseven.bpm.engine.variable.Variables");
    assertThat(longValueOf).isEqualTo(5);
  }

  @Test
  public void shouldLoadScriptsInTheNashornCompatibilityModeOfTheJvm(@TempDir Path directory)
      throws ScriptException, IOException {
    // given: GraalJS initialized before the system property is set, which would else hold for the whole JVM. Set
    // after that, the property reaches the narrowed script engines, which read it at their creation (GraalJS reads it
    // once): the result is the one of a JVM started with the property
    evaluate(sharedResolver(), configuration(LONG), true, "1");
    Path script = Files.writeString(directory.resolve("loaded.js"), "var loadedValue = 42;");
    String load = "load('" + script.toAbsolutePath().toString().replace('\\', '/') + "'); "
        + "sink.set(java.lang.Long.valueOf(loadedValue))";

    System.setProperty("polyglot.js.nashorn-compat", "true");
    try {
      // when
      Object loaded = evaluate(sharedResolver(), configuration(LONG), true, load);

      // then: GraalJS allows all access in this mode
      assertThat(loaded).isEqualTo(42);
    } finally {
      System.clearProperty("polyglot.js.nashorn-compat");
    }
  }

  @Test
  public void shouldKeepTheBindingsOfTheScriptEngineManager() throws ScriptException {
    // given
    DefaultScriptEngineResolver resolver = sharedResolver();
    resolver.getScriptEngineManager().put("globalBean", 7L);

    // when
    Object globalBean = evaluate(resolver, configuration(LONG), true, "sink.set(globalBean)");

    // then
    assertThat(globalBean).isEqualTo(7);
  }

  static Stream<Arguments> optionsOfTheFactory() {
    return Stream.of(
        // js.print
        Arguments.of("typeof print", "function"),
        // js.load
        Arguments.of("typeof load", "function"),
        // js.global-arguments
        Arguments.of("typeof arguments", "object"),
        // js.syntax-extensions: an expression closure of Nashorn
        Arguments.of("(function() 42)()", 42));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("optionsOfTheFactory")
  public void shouldGiveTheNarrowedScriptEngineTheOptionsOfTheFactory(String script, Object expected)
      throws ScriptException {
    // when: the script engine of the factory (mode none) and the one created in its place (mode long)
    Object fromTheFactory = evaluate(sharedResolver(), configuration(NONE), true, script);
    Object narrowed = evaluate(sharedResolver(), configuration(LONG), true, script);

    // then: the options that GraalJSScriptEngine sets when it creates the context itself
    assertThat(fromTheFactory).isEqualTo(expected);
    assertThat(narrowed).isEqualTo(fromTheFactory);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("numbers")
  public void shouldNarrowOnlyJavaLongsInIntRange(String number, Object inModeLong, Object inModeNone)
      throws ScriptException {
    // given
    String script = "sink.set(" + number + ")";

    // when
    Object narrowed = evaluate(sharedResolver(), configuration(LONG), true, script);
    Object notNarrowed = evaluate(sharedResolver(), configuration(NONE), true, script);

    // then: equal values of other types are not equal
    assertThat(narrowed).isEqualTo(inModeLong);
    assertThat(notNarrowed).isEqualTo(inModeNone);
  }

  @Test
  public void shouldKeepALongForAParameterOfTypeNumber() throws ScriptException {
    // when
    Object number = evaluate(sharedResolver(), configuration(LONG), true, "sink.setNumber(java.lang.Long.valueOf(5))");

    // then: the mapping is for parameters of type Object
    assertThat(number).isEqualTo(5L);
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldNotNarrowWithoutHostAccess(boolean useCibSevenNamespace) {
    // given: mode long, but no host access for the scripts
    DefaultScriptEngineResolver resolver = sharedResolver();
    ProcessEngineConfigurationImpl configuration = configuration(LONG).setConfigureScriptEngineHostAccess(false);
    configuration.setUseCibSevenNamespaceInScripting(useCibSevenNamespace);

    // when
    ScriptEngine scriptEngine = resolve(resolver, configuration);

    // then: the script engine of GraalJS as without the narrowing, whose scripts cannot reach a Java object
    assertThat(GraalJsNumberNarrowing.hasOwnHostAccess(scriptEngine)).isFalse();
    assertThatThrownBy(() -> evaluate(resolver, configuration, true, "sink.set(5)"))
        .isInstanceOf(ScriptException.class)
        .hasMessageNotContaining("ENGINE-21004");
  }

  @ParameterizedTest(name = "useCibSevenNamespaceInScripting={0}")
  @MethodSource("paths")
  public void shouldNarrowTheScriptEngineNamedForJavaScript(boolean useCibSevenNamespace) throws ScriptException {
    // given: the script engine for JavaScript named in the configuration
    ProcessEngineConfigurationImpl configuration = configuration(LONG).setScriptEngineNameJavaScript("Graal.js");
    configuration.setUseCibSevenNamespaceInScripting(useCibSevenNamespace);

    // when
    Object longValueOf = evaluate(sharedResolver(), configuration, true, "sink.set(java.lang.Long.valueOf(5))");

    // then
    assertThat(longValueOf).isEqualTo(5);
  }

  /**
   * GraalJS as the WildFly modules of CIB seven load it: everything in one class loader but the classes of
   * org.graalvm.sdk.nativeimage, whose class loader cannot see org.graalvm.sdk.word.
   */
  protected static class GraalJsWithoutWordForNativeImage extends URLClassLoader {
    protected final ClassLoader nativeImage;

    protected GraalJsWithoutWordForNativeImage(URL[] graalJs, ClassLoader nativeImage) {
      super(graalJs, ClassLoader.getPlatformClassLoader());
      this.nativeImage = nativeImage;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      return name.startsWith("org.graalvm.nativeimage.") ? nativeImage.loadClass(name) : super.loadClass(name, resolve);
    }
  }

  protected static URL jarOf(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation();
  }

  @Test
  public void shouldLookUpGraalJsWhereTheTypesOfNativeImageCannotBeLinked() throws Exception {
    // given: Engine.storeCache(Path, WordPointer), a type of org.graalvm.sdk.nativeimage that extends one of
    // org.graalvm.sdk.word: Class.getMethod on Engine links it and fails, as with the WildFly modules
    URL[] graalJs = {jarOf(GraalJSScriptEngine.class), jarOf(Engine.class), jarOf(Truffle.class),
        jarOf(EconomicMap.class), jarOf(WordBase.class)};
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader nativeImage = new URLClassLoader(new URL[] {jarOf(ImageInfo.class)},
            ClassLoader.getPlatformClassLoader());
        URLClassLoader loader = new GraalJsWithoutWordForNativeImage(graalJs, nativeImage)) {
      Class<?> graalJsScriptEngine = loader.loadClass(GraalJSScriptEngine.class.getName());
      // the polyglot implementation is found through the context class loader
      Thread.currentThread().setContextClassLoader(loader);

      // when
      Object hostAccess = GraalJsNumberNarrowing.getHostAccess(new Object(), graalJsScriptEngine, false);

      // then
      assertThat(hostAccess).isInstanceOf(loader.loadClass(HostAccess.class.getName()));
    } finally {
      Thread.currentThread().setContextClassLoader(contextClassLoader);
    }
  }

  /** GraalJS whose polyglot classes cannot be linked, as with a module of an application server that lacks them. */
  protected static class GraalJsWithoutPolyglot extends URLClassLoader {

    protected GraalJsWithoutPolyglot(URL[] graalJs) {
      super(graalJs, ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("org.graalvm.polyglot.")) {
        throw new NoClassDefFoundError(name.replace('.', '/'));
      }
      return super.loadClass(name, resolve);
    }
  }

  @Test
  public void shouldFailWithAnExceptionIfTheHostAccessCannotBeLinked() throws Exception {
    // given: GraalJS whose polyglot classes give a LinkageError
    try (URLClassLoader loader = new GraalJsWithoutPolyglot(new URL[] {jarOf(GraalJSScriptEngine.class)})) {
      Class<?> graalJsScriptEngine = loader.loadClass(GraalJSScriptEngine.class.getName());

      // when/then: ENGINE-21004, an exception that CamundaScriptEngineManager (useCibSevenNamespaceInScripting)
      // catches to try the default path, not the LinkageError itself
      assertThatThrownBy(() -> GraalJsNumberNarrowing.getHostAccess(new Object(), graalJsScriptEngine, false))
          .isInstanceOf(ProcessEngineException.class)
          .hasMessageContaining("ENGINE-21004")
          .hasMessageContaining("NoClassDefFoundError")
          .hasCauseInstanceOf(NoClassDefFoundError.class);
    }
  }

  @Test
  public void shouldFailIfTheNarrowedScriptEngineCannotBeCreated() {
    // given: a script engine that cannot be created again with the host access of the narrowing
    ScriptEngine groovy = new ScriptEngineManager().getEngineByName("groovy");

    // when/then: an error that names the way out, not a script engine that gives other variable types
    assertThatThrownBy(() -> GraalJsNumberNarrowing.createScriptEngine(groovy, false))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("ENGINE-21004")
        // the cause in the message, not only in the stack trace
        .hasMessageContaining("NoSuchMethodException")
        .hasMessageNotContaining("{}")
        .hasMessageContaining("Set javaScriptNumberNarrowing to 'none'");
  }

}
