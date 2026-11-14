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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.script.Bindings;
import javax.script.ScriptContext;
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
 * <li>{@link #NONE}: the types GraalJS gives (Long, Double).</li>
 * </ul>
 * The mapping has the precedence {@code LOW}: of a Java method overloaded for {@code int} and {@code Object}, such
 * as {@code List.remove}, the same one is chosen as without the mapping.
 * <p>
 * All contexts of one polyglot engine must use the same host access. A narrowed script engine therefore runs on a
 * polyglot engine of this class, one per owner, and never on the polyglot engine of the script engine factory:
 * script engines without narrowing use that one with {@code HostAccess.ALL}, for example those of a process
 * application that two process engines with different modes share. GraalJS is used through reflection: the
 * process engine has no compile dependency on it.
 * <p>
 * In the Nashorn compatibility mode ({@code enableScriptEngineNashornCompatibility}), the host access is the one
 * GraalJS uses in that mode (with the conversions of Nashorn) with the same mapping, and the context gets the
 * settings GraalJS gives it in that mode. The system property {@code polyglot.js.nashorn-compat} gives a script
 * engine of the factory these settings too, as GraalJS does.
 */
public final class GraalJsNumberNarrowing {

  /** The types GraalJS gives. */
  public static final String NONE = "none";
  /** A Java long in int range becomes an Integer (the default). */
  public static final String LONG = "long";

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
  /** The system property with which GraalJSScriptEngine creates its contexts in the Nashorn compatibility mode. */
  private static final String NASHORN_COMPAT_SYSTEM_PROPERTY = "polyglot.js.nashorn-compat";
  private static final String NASHORN_COMPAT_OPTION = "js.nashorn-compat";

  /**
   * The script engines that were created with their own host access: the magic attribute
   * {@code polyglot.js.allowHostAccess} would replace it with {@code HostAccess.ALL}.
   */
  private static final Map<ScriptEngine, Boolean> ENGINES_WITH_HOST_ACCESS =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * The GraalJS of each owner: the script engine factory whose script engines are narrowed, or another object that
   * creates script engines. Weak keys, and nothing in a value refers to its key: the entry goes with the owner (for
   * example with the script engine manager of an undeployed application).
   */
  private static final Map<Object, Graal> GRAALS = new WeakHashMap<>();

  private GraalJsNumberNarrowing() {
  }

  /**
   * @return the given mode in lower case
   * @throws ProcessEngineException if the mode is neither {@link #LONG} nor {@link #NONE}
   */
  public static String checkMode(String mode) {
    String lowerCase = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
    if (NONE.equals(lowerCase) || LONG.equals(lowerCase)) {
      return lowerCase;
    }
    throw new ProcessEngineException("javaScriptNumberNarrowing must be '" + LONG + "' or '" + NONE + "', not '"
        + mode + "'");
  }

  /** @return true if the mode changes the type of numbers */
  public static boolean isNarrowing(String mode) {
    return LONG.equals(mode);
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
   * Creates a GraalJS script engine like the given one, which its script engine factory created: with the default
   * options of the factory and the host access of this class, on a polyglot engine of this class for that factory,
   * and with the global bindings of the given one (those of its script engine manager).
   *
   * @param nashornCompatibility whether the process engine enables the Nashorn compatibility mode
   * @throws ProcessEngineException if that script engine cannot be created: a script that ran with the types GraalJS
   *   gives would store other variable types than its process expects
   */
  public static ScriptEngine createScriptEngine(ScriptEngine factoryScriptEngine, boolean nashornCompatibility) {
    try {
      Graal graal = graal(factoryScriptEngine.getFactory(), factoryScriptEngine.getClass());
      Object polyglotEngine = graal.polyglotEngine(nashornCompatibility);
      Object builder = graal.newContextBuilder(polyglotEngine);
      if (nashornCompatibility || Boolean.getBoolean(NASHORN_COMPAT_SYSTEM_PROPERTY)) {
        // the settings GraalJSScriptEngine gives a context in this mode, also when only the system property asks
        // for it; the host access is the one the factory's script engine ends with: of Nashorn with the property of
        // the process engine, HostAccess.ALL with the system property alone (polyglot.js.allowHostAccess)
        builder = graal.nashornCompatibility(builder);
      }
      builder = graal.allowHostAccess.invoke(builder, graal.hostAccess(nashornCompatibility));

      ScriptEngine scriptEngine = (ScriptEngine) graal.create.invoke(null, polyglotEngine, builder);
      Bindings globalBindings = factoryScriptEngine.getBindings(ScriptContext.GLOBAL_SCOPE);
      if (globalBindings != null) {
        scriptEngine.setBindings(globalBindings, ScriptContext.GLOBAL_SCOPE);
      }
      markOwnHostAccess(scriptEngine);
      return scriptEngine;

    } catch (InvocationTargetException e) {
      throw LOG.graalJsNumberNarrowingFailed(LONG, e.getCause() != null ? e.getCause() : e);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw LOG.graalJsNumberNarrowingFailed(LONG, e);
    }
  }

  /**
   * @param owner the object that creates script engines each with a polyglot engine of its own
   * @param graalJsScriptEngineClass the class {@code GraalJSScriptEngine}
   * @param nashornCompatibility whether the process engine enables the Nashorn compatibility mode
   * @return {@code HostAccess.ALL}, or the host access of GraalJS in the Nashorn compatibility mode, with the
   *   number mapping: the same object for the same owner and mode
   */
  public static Object getHostAccess(Object owner, Class<?> graalJsScriptEngineClass, boolean nashornCompatibility)
      throws ReflectiveOperationException {
    return graal(owner, graalJsScriptEngineClass).hostAccess(nashornCompatibility);
  }

  private static synchronized Graal graal(Object owner, Class<?> graalJsScriptEngineClass)
      throws ReflectiveOperationException {
    Graal graal = GRAALS.get(owner);
    if (graal == null) {
      graal = new Graal(graalJsScriptEngineClass);
      GRAALS.put(owner, graal);
    }
    return graal;
  }

  /**
   * GraalJS for one owner: the methods looked up once, and per host access (with or without the Nashorn
   * compatibility mode) the host access and the polyglot engine that the narrowed script engines of the owner share.
   */
  private static final class Graal {

    final Class<?> graalJsScriptEngineClass;
    final ClassLoader loader;
    final Method newContextBuilder;
    final Method allowExperimentalOptions;
    final Method option;
    final Method allowHostAccess;
    final Method allowAllAccess;
    final Method useSystemExit;
    final Method create;
    final Method newEngineBuilder;
    final Method allowExperimentalEngineOptions;
    final Method buildEngine;
    final Method getLanguages;
    final Class<?> optionDescriptorsClass;
    final Class<?> hostAccessClass;

    /** Index 0: without, 1: with the Nashorn compatibility mode. */
    final Object[] hostAccesses = new Object[2];
    /** Weak, as the polyglot engine of the factory: it goes when no script engine uses it, and comes again. */
    @SuppressWarnings("unchecked")
    final WeakReference<Object>[] polyglotEngines = new WeakReference[2];
    Boolean knowsCharsetOption;

    Graal(Class<?> graalJsScriptEngineClass) throws ReflectiveOperationException {
      this.graalJsScriptEngineClass = graalJsScriptEngineClass;
      loader = graalJsScriptEngineClass.getClassLoader();
      Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context", false, loader);
      Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine", false, loader);
      hostAccessClass = Class.forName("org.graalvm.polyglot.HostAccess", false, loader);
      Class<?> contextBuilderClass = Class.forName("org.graalvm.polyglot.Context$Builder", false, loader);
      Class<?> engineBuilderClass = Class.forName("org.graalvm.polyglot.Engine$Builder", false, loader);

      newContextBuilder = contextClass.getMethod("newBuilder", String[].class);
      allowExperimentalOptions = contextBuilderClass.getMethod("allowExperimentalOptions", boolean.class);
      option = contextBuilderClass.getMethod("option", String.class, String.class);
      allowHostAccess = contextBuilderClass.getMethod("allowHostAccess", hostAccessClass);
      allowAllAccess = contextBuilderClass.getMethod("allowAllAccess", boolean.class);
      useSystemExit = contextBuilderClass.getMethod("useSystemExit", boolean.class);
      create = graalJsScriptEngineClass.getMethod("create", engineClass, contextBuilderClass);
      newEngineBuilder = engineClass.getMethod("newBuilder");
      allowExperimentalEngineOptions = engineBuilderClass.getMethod("allowExperimentalOptions", boolean.class);
      buildEngine = engineBuilderClass.getMethod("build");
      getLanguages = engineClass.getMethod("getLanguages");
      optionDescriptorsClass = Class.forName("org.graalvm.options.OptionDescriptors", false, loader);
    }

    /**
     * @return {@code HostAccess.ALL}, or in the Nashorn compatibility mode the host access GraalJSScriptEngine uses in
     *   that mode, with the number mapping
     */
    synchronized Object hostAccess(boolean nashornCompatibility) throws ReflectiveOperationException {
      int index = nashornCompatibility ? 1 : 0;
      if (hostAccesses[index] == null) {
        Object base;
        if (nashornCompatibility) {
          Field nashornHostAccess = graalJsScriptEngineClass.getDeclaredField("NASHORN_HOST_ACCESS");
          nashornHostAccess.setAccessible(true);
          base = nashornHostAccess.get(null);
        } else {
          base = hostAccessClass.getField("ALL").get(null);
        }
        hostAccesses[index] = createHostAccess(loader, base);
      }
      return hostAccesses[index];
    }

    /** @return the polyglot engine for the host access, built as GraalJSEngineFactory builds its own */
    synchronized Object polyglotEngine(boolean nashornCompatibility) throws ReflectiveOperationException {
      int index = nashornCompatibility ? 1 : 0;
      Object engine = polyglotEngines[index] == null ? null : polyglotEngines[index].get();
      if (engine == null) {
        Object builder = allowExperimentalEngineOptions.invoke(newEngineBuilder.invoke(null), true);
        engine = buildEngine.invoke(builder);
        polyglotEngines[index] = new WeakReference<>(engine);
      }
      return engine;
    }

    /** @return the builder with the settings GraalJSScriptEngine gives a context in the Nashorn compatibility mode */
    Object nashornCompatibility(Object builder) throws ReflectiveOperationException {
      builder = option.invoke(builder, NASHORN_COMPAT_OPTION, "true");
      builder = allowAllAccess.invoke(builder, true);
      return useSystemExit.invoke(builder, true);
    }

    /** @return a context builder with the options that GraalJSScriptEngine sets when it creates one itself */
    Object newContextBuilder(Object polyglotEngine) throws ReflectiveOperationException {
      Object builder = newContextBuilder.invoke(null, (Object) new String[] {"js"});
      builder = allowExperimentalOptions.invoke(builder, true);
      for (String[] nameAndValue : DEFAULT_CONTEXT_OPTIONS) {
        builder = option.invoke(builder, nameAndValue[0], nameAndValue[1]);
      }
      if (knowsCharsetOption(polyglotEngine)) {
        builder = option.invoke(builder, CHARSET_OPTION[0], CHARSET_OPTION[1]);
      }
      return builder;
    }

    synchronized boolean knowsCharsetOption(Object polyglotEngine) throws ReflectiveOperationException {
      if (knowsCharsetOption == null) {
        Map<?, ?> languages = (Map<?, ?>) getLanguages.invoke(polyglotEngine);
        Object javaScript = languages.get("js");
        Object options = javaScript == null ? null : javaScript.getClass().getMethod("getOptions").invoke(javaScript);
        knowsCharsetOption = options != null
            && optionDescriptorsClass.getMethod("get", String.class).invoke(options, CHARSET_OPTION[0]) != null;
      }
      return knowsCharsetOption;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object createHostAccess(ClassLoader graalJsClassLoader, Object baseHostAccess)
      throws ReflectiveOperationException {
    Class<?> hostAccessClass = Class.forName("org.graalvm.polyglot.HostAccess", false, graalJsClassLoader);
    Class<?> valueClass = Class.forName("org.graalvm.polyglot.Value", false, graalJsClassLoader);
    Class<?> precedenceClass = Class.forName("org.graalvm.polyglot.HostAccess$TargetMappingPrecedence", false,
        graalJsClassLoader);

    Method isNumber = valueClass.getMethod("isNumber");
    Method fitsInInt = valueClass.getMethod("fitsInInt");
    Method as = valueClass.getMethod("as", Class.class);
    Method asInt = valueClass.getMethod("asInt");

    // a number in int range that GraalJS would hand over as a Long; for a JavaScript int it gives an Integer already
    Predicate<Object> narrows = value -> {
      try {
        if (!(Boolean) isNumber.invoke(value) || !(Boolean) fitsInInt.invoke(value)) {
          return false;
        }
        return as.invoke(value, Number.class) instanceof Long;
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

    Object builder = hostAccessClass.getMethod("newBuilder", hostAccessClass).invoke(null, baseHostAccess);
    builder.getClass()
        .getMethod("targetTypeMapping", Class.class, Class.class, Predicate.class, Function.class, precedenceClass)
        .invoke(builder, valueClass, Object.class, narrows, toInteger, Enum.valueOf((Class) precedenceClass, "LOW"));
    return builder.getClass().getMethod("build").invoke(builder);
  }

}
