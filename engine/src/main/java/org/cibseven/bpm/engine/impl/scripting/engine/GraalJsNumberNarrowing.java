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
package org.cibseven.bpm.engine.impl.scripting.engine;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.script.ScriptEngine;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.ProcessEngineLogger;
import org.cibseven.bpm.engine.impl.scripting.ScriptLogger;

/**
 * Keeps the Java type of the numbers that JavaScript scripts on GraalJS hand to Java as CIB seven 2.2 did.
 * <p>
 * Since GraalJS 24, a Java {@code long} stays a long inside JavaScript, and arithmetic with it gives a double. A
 * script that passes {@code execution.getVariable('count')}, {@code java.lang.Long.valueOf(7)} or the count of a
 * query to {@code execution.setVariable} therefore stores a {@code Long} variable, where GraalJS 21 (CIB seven 2.2)
 * stored an {@code Integer}. This class builds the polyglot {@code HostAccess} of the GraalJS contexts of the
 * process engine: {@code HostAccess.ALL} with a target type mapping that turns such a number into an
 * {@code Integer} when a script hands it to a Java parameter of type {@code Object} (such as
 * {@code setVariable(String, Object)}) or returns it as its result. The modes:
 * <ul>
 * <li>{@link #LONG} (the default): a Java long in int range becomes an Integer. A double stays a Double, also the
 * result of arithmetic with a long ({@code lng + 1} gives {@code 6.0}).</li>
 * <li>{@link #INTEGRAL}: every integral number in int range that is not an Integer yet, also a double such as
 * {@code lng + 1}. A Double variable with an integral value such as {@code 3.0} then becomes an Integer too.</li>
 * <li>{@link #NONE}: the types GraalJS gives (Long, Double).</li>
 * </ul>
 * The mapping has the precedence {@code LOW}: of a Java method overloaded for {@code int} and {@code Object}, such
 * as {@code List.remove}, the same one is chosen as without the mapping.
 * <p>
 * All contexts of one polyglot engine must use the same host access, so there is one host access per owner of a
 * polyglot engine (the script engine factory) and mode. GraalJS is used through reflection: the process engine has
 * no compile dependency on it.
 */
public final class GraalJsNumberNarrowing {

  /** The types GraalJS gives. */
  public static final String NONE = "none";
  /** A Java long in int range becomes an Integer (the default). */
  public static final String LONG = "long";
  /** Every integral number in int range becomes an Integer, also a double such as 6.0. */
  public static final String INTEGRAL = "integral";

  private static final ScriptLogger LOG = ProcessEngineLogger.SCRIPT_LOGGER;

  /** The options that GraalJSScriptEngine sets when it creates the context configuration itself (GraalJS 25.0.1). */
  private static final String[][] DEFAULT_CONTEXT_OPTIONS = {
      {"js.syntax-extensions", "true"},
      {"js.load", "true"},
      {"js.print", "true"},
      {"js.global-arguments", "true"}
  };
  /** Set as well where the option exists: GraalJS 21 does not know it. */
  private static final String[] CHARSET_OPTION = {"js.charset", "UTF-8"};

  /**
   * The script engines that were created with their own host access: the magic attribute
   * {@code polyglot.js.allowHostAccess} would replace it with {@code HostAccess.ALL}.
   */
  private static final Map<ScriptEngine, Boolean> ENGINES_WITH_HOST_ACCESS =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * The host access per owner and mode. An owner is the script engine factory whose polyglot engine the contexts
   * share, or another object that creates script engines. Weak keys: the host access does not refer to its owner,
   * so the entry goes with the owner (for example with the script engine manager of an undeployed application).
   */
  private static final Map<Object, Map<String, Object>> HOST_ACCESSES = new WeakHashMap<>();

  /** Whether the JavaScript language of a GraalJS class loader knows {@code js.charset}. */
  private static final Map<ClassLoader, Boolean> KNOWS_CHARSET_OPTION = new WeakHashMap<>();

  /** The failure is logged once, not at every script. */
  private static volatile boolean failureLogged;

  private GraalJsNumberNarrowing() {
  }

  /**
   * @return the given mode in lower case
   * @throws ProcessEngineException if the mode is none of {@link #NONE}, {@link #LONG}, {@link #INTEGRAL}
   */
  public static String checkMode(String mode) {
    String lowerCase = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
    if (NONE.equals(lowerCase) || LONG.equals(lowerCase) || INTEGRAL.equals(lowerCase)) {
      return lowerCase;
    }
    throw new ProcessEngineException("javaScriptNumberNarrowing must be one of '" + LONG + "', '" + INTEGRAL
        + "', '" + NONE + "', not '" + mode + "'");
  }

  /** @return true if the mode changes the type of numbers */
  public static boolean isNarrowing(String mode) {
    return LONG.equals(mode) || INTEGRAL.equals(mode);
  }

  /** @return true if the script engine was created with the host access of this class */
  public static boolean hasOwnHostAccess(ScriptEngine scriptEngine) {
    return scriptEngine != null && ENGINES_WITH_HOST_ACCESS.containsKey(scriptEngine);
  }

  /** Records that the script engine was created with the host access of this class. */
  public static void markOwnHostAccess(ScriptEngine scriptEngine) {
    ENGINES_WITH_HOST_ACCESS.put(scriptEngine, Boolean.TRUE);
  }

  /**
   * Creates a GraalJS script engine like the given one, which its script engine factory created: on the
   * polyglot engine of that factory, with the default options of the factory and with the host access of the
   * mode. If that fails, it logs a warning and returns the given script engine.
   */
  public static ScriptEngine createScriptEngine(ScriptEngine factoryScriptEngine, String mode) {
    try {
      ClassLoader graalJsClassLoader = factoryScriptEngine.getClass().getClassLoader();
      Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context", false, graalJsClassLoader);
      Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine", false, graalJsClassLoader);
      Class<?> hostAccessClass = Class.forName("org.graalvm.polyglot.HostAccess", false, graalJsClassLoader);

      Object factory = factoryScriptEngine.getFactory();
      Object polyglotEngine = factory.getClass().getMethod("getPolyglotEngine").invoke(factory);

      Object builder = contextClass.getMethod("newBuilder", String[].class).invoke(null, (Object) new String[] {"js"});
      Class<?> builderClass = builder.getClass();
      builder = builderClass.getMethod("allowExperimentalOptions", boolean.class).invoke(builder, true);
      Method option = builderClass.getMethod("option", String.class, String.class);
      for (String[] nameAndValue : DEFAULT_CONTEXT_OPTIONS) {
        builder = option.invoke(builder, nameAndValue[0], nameAndValue[1]);
      }
      if (knowsCharsetOption(graalJsClassLoader, engineClass, polyglotEngine)) {
        builder = option.invoke(builder, CHARSET_OPTION[0], CHARSET_OPTION[1]);
      }
      builder = builderClass.getMethod("allowHostAccess", hostAccessClass)
          .invoke(builder, getHostAccess(factory, graalJsClassLoader, mode));

      ScriptEngine scriptEngine = (ScriptEngine) factoryScriptEngine.getClass()
          .getMethod("create", engineClass, builderClass)
          .invoke(null, polyglotEngine, builder);
      markOwnHostAccess(scriptEngine);
      return scriptEngine;

    } catch (ReflectiveOperationException | RuntimeException e) {
      if (!failureLogged) {
        failureLogged = true;
        LOG.logGraalJsNumberNarrowingFailed(mode, e);
      }
      return factoryScriptEngine;
    }
  }

  /**
   * @param owner the script engine factory whose polyglot engine the contexts share, or the object that creates
   *   script engines each with a polyglot engine of its own
   * @return {@code HostAccess.ALL} with the number mapping of the mode: the same object for the same owner and mode
   */
  public static synchronized Object getHostAccess(Object owner, ClassLoader graalJsClassLoader, String mode)
      throws ReflectiveOperationException {
    Map<String, Object> byMode = HOST_ACCESSES.computeIfAbsent(owner, key -> new HashMap<>());
    Object hostAccess = byMode.get(mode);
    if (hostAccess == null) {
      hostAccess = createHostAccess(graalJsClassLoader, mode);
      byMode.put(mode, hostAccess);
    }
    return hostAccess;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object createHostAccess(ClassLoader graalJsClassLoader, String mode)
      throws ReflectiveOperationException {
    Class<?> hostAccessClass = Class.forName("org.graalvm.polyglot.HostAccess", false, graalJsClassLoader);
    Class<?> valueClass = Class.forName("org.graalvm.polyglot.Value", false, graalJsClassLoader);
    Class<?> precedenceClass = Class.forName("org.graalvm.polyglot.HostAccess$TargetMappingPrecedence", false,
        graalJsClassLoader);

    Method isNumber = valueClass.getMethod("isNumber");
    Method fitsInInt = valueClass.getMethod("fitsInInt");
    Method as = valueClass.getMethod("as", Class.class);
    Method asInt = valueClass.getMethod("asInt");
    boolean integral = INTEGRAL.equals(mode);

    // a JavaScript number in int range that GraalJS would hand over as a Long (mode long), or as a Long or a Double
    // (mode integral); for a JavaScript int it gives an Integer already
    Predicate<Object> narrows = value -> {
      try {
        if (!(Boolean) isNumber.invoke(value) || !(Boolean) fitsInInt.invoke(value)) {
          return false;
        }
        Object number = as.invoke(value, Number.class);
        return integral ? !(number instanceof Integer) : number instanceof Long;
      } catch (ReflectiveOperationException e) {
        return false;
      }
    };
    Function<Object, Object> toInteger = value -> {
      try {
        return asInt.invoke(value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    };

    Object all = hostAccessClass.getField("ALL").get(null);
    Object builder = hostAccessClass.getMethod("newBuilder", hostAccessClass).invoke(null, all);
    builder.getClass()
        .getMethod("targetTypeMapping", Class.class, Class.class, Predicate.class, Function.class, precedenceClass)
        .invoke(builder, valueClass, Object.class, narrows, toInteger, Enum.valueOf((Class) precedenceClass, "LOW"));
    return builder.getClass().getMethod("build").invoke(builder);
  }

  /** @return true if the JavaScript language of the polyglot engine knows the option {@code js.charset} */
  private static synchronized boolean knowsCharsetOption(ClassLoader graalJsClassLoader, Class<?> engineClass,
      Object polyglotEngine) throws ReflectiveOperationException {
    Boolean knows = KNOWS_CHARSET_OPTION.get(graalJsClassLoader);
    if (knows == null) {
      Class<?> optionDescriptorsClass = Class.forName("org.graalvm.options.OptionDescriptors", false,
          graalJsClassLoader);
      Map<?, ?> languages = (Map<?, ?>) engineClass.getMethod("getLanguages").invoke(polyglotEngine);
      Object javaScript = languages.get("js");
      Object options = javaScript == null ? null : javaScript.getClass().getMethod("getOptions").invoke(javaScript);
      knows = options != null
          && optionDescriptorsClass.getMethod("get", String.class).invoke(options, CHARSET_OPTION[0]) != null;
      KNOWS_CHARSET_OPTION.put(graalJsClassLoader, knows);
    }
    return knows;
  }

}
