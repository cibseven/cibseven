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
package org.cibseven.bpm.impl.juel;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jakarta.el.ELResolver;
import jakarta.el.FunctionMapper;

/**
 * Deny list for JUEL expressions and scripts ({@code deniedExpressionAccess}); other script languages
 * and the Scala-based FEEL engine are not restricted. Comma-separated entries, matched in full,
 * {@code *} as wildcard:
 * <ul>
 *   <li>{@code getClass}: a property or method name; property {@code foo} also as {@code getFoo}/{@code isFoo}.
 *       Map keys are not checked by name.</li>
 *   <li>{@code java.lang.Runtime}: a type (with nested types, binary name {@code Map$Entry}) or package, checked
 *       against the accessed object with its supertypes, every result and the declaring type of a function.
 *       A {@link Class} counts as the type it represents, a member as its declaring type.</li>
 *   <li>{@code lang:import}: an EL function, {@code :name} one without prefix.</li>
 *   <li>{@code com.customer.Admin#delete}: a member of that type and its subtypes.</li>
 * </ul>
 * <p>Entries extend the {@link #DEFAULT_DENIED_EXPRESSION_ACCESS defaults}; {@code -entry} removes a default
 * ({@code getClass()} needs {@code -class,-getClass,-java.lang.Class}), mode {@code off} disables the list.
 * The {@link #PROTECTED_TYPES} cannot be removed. Thread-safe; report deduplication is approximate.</p>
 */
public final class ExpressionAccessPolicy {

  /** Denied by default: reflection, {@code lang:import}, the engine configuration and process start. */
  public static final String DEFAULT_DENIED_EXPRESSION_ACCESS = "class,getClass,"
      + "java.lang.Class,java.lang.ClassLoader,java.lang.reflect.AccessibleObject,"
      // not the package: Class implements java.lang.invoke.TypeDescriptor
      + "java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandles,"
      + "lang:import,"
      // the type, not the package with customer extension points
      + "org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl,"
      + "java.lang.Runtime,java.lang.ProcessBuilder";

  /** Always denied while active: the EL API and this filter itself. */
  public static final String PROTECTED_TYPES = "jakarta.el,javax.el,org.cibseven.bpm.impl.juel";

  // report each violation once; bounded, starts over when full
  private static final int MAX_DEDUPLICATED_REPORTS = 10_000;
  private static final int MAX_NAME_LENGTH_IN_MESSAGE = 100;
  private static final int MAX_EXPRESSION_LENGTH_IN_MESSAGE = 200;

  // the expression on this thread, for messages
  private static final ThreadLocal<String> CURRENT_EXPRESSION = new ThreadLocal<>();

  private static final Consumer<String> DEFAULT_REPORTER = message ->
      Logger.getLogger(ExpressionAccessPolicy.class.getName()).log(Level.WARNING, message);

  /** The defaults, enforced; declared after the fields it uses. */
  public static final ExpressionAccessPolicy DEFAULT = create(null, ExpressionHardeningMode.ENFORCE);

  private final ExpressionHardeningMode mode;
  private final Set<String> deniedEntries;
  private final Consumer<String> reporter;

  // names without wildcard: exact lookup, and getter entries by the property they resolve
  private final Set<String> literalNames = new HashSet<>();
  private final Map<String, String> getterEntriesByProperty = new HashMap<>();
  private final List<Rule> wildcardNames = new ArrayList<>();
  private final List<Rule> typeRules = new ArrayList<>();
  private final List<Rule> protectedTypeRules = new ArrayList<>();
  private final List<Rule> configuredTypeRules = new ArrayList<>();
  private final List<Rule> functionRules = new ArrayList<>();
  private final List<MemberRule> memberRules = new ArrayList<>();

  private final Set<String> reported = ConcurrentHashMap.newKeySet();
  private final ClassValue<String> deniedTypeEntry = new ClassValue<String>() {
    @Override
    protected String computeValue(Class<?> type) {
      String entry = findTypeEntry(type, typeRules);
      return entry != null ? entry : "";
    }
  };

  private ExpressionAccessPolicy(ExpressionHardeningMode mode, Set<String> deniedEntries, Consumer<String> reporter) {
    this.mode = mode;
    this.deniedEntries = Collections.unmodifiableSet(deniedEntries);
    this.reporter = reporter;
    for (String entry : PROTECTED_TYPES.split(",")) {
      Rule rule = new Rule(entry, typePattern(entry));
      typeRules.add(rule);
      protectedTypeRules.add(rule);
    }
    for (String entry : deniedEntries) {
      int hash = entry.indexOf('#');
      if (hash >= 0) {
        memberRules.add(new MemberRule(entry, typePattern(entry.substring(0, hash)), namePattern(entry.substring(hash + 1))));
      } else if (entry.indexOf(':') >= 0) {
        functionRules.add(new Rule(entry, namePattern(entry)));
      } else if (entry.indexOf('.') >= 0) {
        Rule rule = new Rule(entry, typePattern(entry));
        typeRules.add(rule);
        configuredTypeRules.add(rule);
      } else if (entry.indexOf('*') >= 0) {
        wildcardNames.add(new Rule(entry, namePattern(entry)));
      } else {
        literalNames.add(entry);
        String property = getterProperty(entry);
        if (property != null) {
          getterEntriesByProperty.putIfAbsent(property, entry);
        }
      }
    }
  }

  /**
   * @param deniedExpressionAccess comma-separated entries added to the defaults, null or blank for the defaults only
   * @param mode null means {@link ExpressionHardeningMode#ENFORCE}
   * @throws IllegalArgumentException for a syntactically invalid entry or the removal of a non-default entry
   */
  public static ExpressionAccessPolicy create(String deniedExpressionAccess, ExpressionHardeningMode mode) {
    return new ExpressionAccessPolicy(
        mode != null ? mode : ExpressionHardeningMode.ENFORCE,
        parse(deniedExpressionAccess),
        DEFAULT_REPORTER);
  }

  /**
   * @param reporter receives the message of each violation in {@link ExpressionHardeningMode#REPORT report} mode
   */
  public ExpressionAccessPolicy withReporter(Consumer<String> reporter) {
    return new ExpressionAccessPolicy(mode, new LinkedHashSet<>(deniedEntries), reporter != null ? reporter : DEFAULT_REPORTER);
  }

  /** Whether violations in report mode go to {@code java.util.logging}, i.e. no reporter was set. */
  public boolean hasDefaultReporter() {
    return reporter == DEFAULT_REPORTER;
  }

  static Set<String> parse(String deniedExpressionAccess) {
    List<String> defaults = Arrays.asList(DEFAULT_DENIED_EXPRESSION_ACCESS.split(","));
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    if (deniedExpressionAccess != null) {
      for (String raw : deniedExpressionAccess.split(",")) {
        String entry = raw.trim();
        if (entry.isEmpty()) {
          continue;
        }
        if (entry.startsWith("-")) {
          String target = entry.substring(1).trim();
          if (!defaults.contains(target)) {
            throw invalid(entry, "only default entries can be removed, the defaults are " + DEFAULT_DENIED_EXPRESSION_ACCESS);
          }
          removed.add(target);
        } else {
          validate(entry);
          added.add(entry);
        }
      }
    }
    for (String entry : added) {
      if (removed.contains(entry)) {
        throw invalid(entry, "the entry is added and removed at the same time");
      }
    }
    Set<String> entries = new LinkedHashSet<>();
    for (String entry : defaults) {
      if (!removed.contains(entry)) {
        entries.add(entry);
      }
    }
    entries.addAll(added);
    return entries;
  }

  private static void validate(String entry) {
    for (int i = 0; i < entry.length(); i++) {
      char c = entry.charAt(i);
      boolean javaName = Character.isJavaIdentifierPart(c) && !Character.isIdentifierIgnorable(c);
      if (!javaName && c != '.' && c != '*' && c != ':' && c != '#') {
        throw invalid(entry, "an entry consists of Java names, '.', ':', '#' and '*' only");
      }
    }
    if (entry.replaceAll("[.:#*]", "").isEmpty()) {
      throw invalid(entry, "an entry needs a name, an entry of wildcards only would deny everything");
    }
    int hash = entry.indexOf('#');
    if (hash >= 0) {
      if (entry.indexOf('#', hash + 1) >= 0 || entry.indexOf(':') >= 0) {
        throw invalid(entry, "a member entry has the form Type#member");
      }
      if (hash == 0 || hash == entry.length() - 1) {
        throw invalid(entry, "a member entry needs a type and a member, e.g. com.customer.Admin#delete");
      }
      validateType(entry, entry.substring(0, hash));
      if (entry.indexOf('.', hash) >= 0) {
        throw invalid(entry, "the member of a member entry is a name without '.'");
      }
      return;
    }
    int colon = entry.indexOf(':');
    if (colon >= 0) {
      if (entry.indexOf(':', colon + 1) >= 0 || colon == entry.length() - 1) {
        throw invalid(entry, "a function entry has the form prefix:name or :name");
      }
      if (entry.indexOf('.', colon) >= 0) {
        throw invalid(entry, "the name of a function entry has no '.'");
      }
      return;
    }
    if (entry.indexOf('.') >= 0) {
      validateType(entry, entry);
    }
  }

  private static void validateType(String entry, String type) {
    if (type.startsWith(".") || type.endsWith(".") || type.contains("..")) {
      throw invalid(entry, "a type entry must not have empty segments");
    }
  }

  private static IllegalArgumentException invalid(String entry, String reason) {
    return new IllegalArgumentException("Invalid deniedExpressionAccess entry '" + entry + "': " + reason);
  }

  public ExpressionHardeningMode getMode() {
    return mode;
  }

  /** The effective entries: defaults minus removed ones, plus added ones; without the {@link #PROTECTED_TYPES}. */
  public Set<String> getDeniedEntries() {
    return deniedEntries;
  }

  /** Whether expressions are checked at all. */
  public boolean isActive() {
    return mode != ExpressionHardeningMode.OFF;
  }

  /** @return the resolver, checked against this policy if {@link #isActive() active}; nested restrictions stay */
  public ELResolver restrict(ELResolver resolver) {
    if (resolver == null || !isActive()) {
      return resolver;
    }
    if (resolver instanceof RestrictedELResolver && ((RestrictedELResolver) resolver).getPolicy() == this) {
      return resolver;
    }
    return new RestrictedELResolver(resolver, this);
  }

  /** @return the mapper, checked against this policy if {@link #isActive() active} */
  public FunctionMapper restrict(FunctionMapper functionMapper) {
    if (functionMapper == null || !isActive()) {
      return functionMapper;
    }
    if (functionMapper instanceof RestrictedFunctionMapper && ((RestrictedFunctionMapper) functionMapper).getPolicy() == this) {
      return functionMapper;
    }
    return new RestrictedFunctionMapper(functionMapper, this);
  }

  /** Checks a property or method access; a top-level identifier ({@code base == null}) is not checked. */
  void checkAccess(Object base, Object property, boolean invocation) {
    if (base == null || property == null) {
      return;
    }
    Class<?> type = base.getClass();
    String typeEntry = findValueTypeEntry(base);
    if (typeEntry != null) {
      denyAccess(property, type, typeEntry);
      return;
    }
    // map keys are data; a map's methods are still checked
    if (!invocation && base instanceof Map) {
      return;
    }
    String name = nameOf(property);
    boolean bean = !invocation && RestrictedELResolver.isBean(base);
    String entry = findNameEntry(name, bean);
    if (entry == null) {
      entry = findMemberEntry(type, name, bean);
    }
    if (entry != null) {
      denyAccess(property, type, entry);
    }
  }

  /** Checks the type of a value; {@code base}/{@code property} where it was read from, may be null. */
  void checkResult(Object base, Object property, Object value) {
    if (value == null) {
      return;
    }
    String typeEntry = findValueTypeEntry(value);
    if (typeEntry != null) {
      deny("Expression result of type " + value.getClass().getName()
          + " is denied by deniedExpressionAccess entry '" + typeEntry + "'", typeEntry + "|result|" + value.getClass().getName());
      return;
    }
    // a map key answered by a bean resolver, e.g. map['class']
    if (base instanceof Map && property != null && value instanceof Class) {
      String entry = findNameEntry(nameOf(property), false);
      if (entry != null) {
        denyAccess(property, base.getClass(), entry);
      }
    }
  }

  /** Checks an EL function, by its name and by the type that declares its method. */
  void checkFunction(String prefix, String localName, Method method) {
    String[] violation = findFunctionViolation(prefix, localName, method);
    if (violation != null) {
      deny(violation[0], violation[1]);
    }
  }

  /** Whether enforce mode blocks the function, without reporting; {@code method} may be null (name only). */
  public boolean blocksFunction(String prefix, String localName, Method method) {
    return mode == ExpressionHardeningMode.ENFORCE && findFunctionViolation(prefix, localName, method) != null;
  }

  // {message, deduplication key} of the first entry that denies the function, or null
  private String[] findFunctionViolation(String prefix, String localName, Method method) {
    String name = (prefix != null ? prefix.trim() : "") + ":" + localName;
    for (Rule rule : functionRules) {
      if (rule.matches(name)) {
        return new String[] {"Expression function '" + sanitize(name) + "' is denied by deniedExpressionAccess entry '" + rule.entry + "'",
            rule.entry + "|function|" + name};
      }
    }
    if (method == null) {
      return null;
    }
    Class<?> declaringType = method.getDeclaringClass();
    // protected types: the declaring class only (FEEL declares functions in a FunctionMapper subclass)
    String typeEntry = findTypeEntryOfClass(declaringType, protectedTypeRules);
    if (typeEntry == null && !configuredTypeRules.isEmpty()) {
      typeEntry = findTypeEntry(declaringType, configuredTypeRules);
    }
    if (typeEntry != null) {
      return new String[] {"Expression function '" + sanitize(name) + "' declared by " + declaringType.getName()
          + " is denied by deniedExpressionAccess entry '" + typeEntry + "'", typeEntry + "|function|" + name};
    }
    // no resolver sees a function's result: check the declared return type
    String returnTypeEntry = findTypeEntry(method.getReturnType());
    if (returnTypeEntry != null) {
      return new String[] {"Expression function '" + sanitize(name) + "' returns " + method.getReturnType().getName()
          + ", denied by deniedExpressionAccess entry '" + returnTypeEntry + "'", returnTypeEntry + "|function|" + name};
    }
    for (MemberRule rule : memberRules) {
      if (rule.name.matches(method.getName()) && findTypeEntry(declaringType, Collections.singletonList(rule.type)) != null) {
        return new String[] {"Expression function '" + sanitize(name) + "' (" + declaringType.getName() + "#" + method.getName()
            + ") is denied by deniedExpressionAccess entry '" + rule.entry + "'", rule.entry + "|function|" + name};
      }
    }
    return null;
  }

  private void denyAccess(Object property, Class<?> type, String entry) {
    deny("Expression access to '" + sanitize(nameOf(property)) + "' on " + type.getName()
        + " is denied by deniedExpressionAccess entry '" + entry + "'", entry + "|access|" + type.getName());
  }

  /** @param deduplicationKey entry and type, not the accessed name, which can come from data */
  private void deny(String message, String deduplicationKey) {
    String expression = CURRENT_EXPRESSION.get();
    if (expression != null) {
      message = message + " in expression '" + sanitize(expression, MAX_EXPRESSION_LENGTH_IN_MESSAGE) + "'";
    }
    if (mode == ExpressionHardeningMode.ENFORCE) {
      throw new ExpressionAccessDeniedException(message);
    }
    // once per violation and expression
    String key = expression != null ? deduplicationKey + "|" + expressionKey(expression) : deduplicationKey;
    if (reported.size() >= MAX_DEDUPLICATED_REPORTS) {
      reported.clear();
    }
    if (reported.add(key)) {
      reporter.accept(message);
    }
  }

  // bounded: prefix, length and hash code
  private static String expressionKey(String expression) {
    if (expression.length() <= MAX_EXPRESSION_LENGTH_IN_MESSAGE) {
      return expression;
    }
    return expression.substring(0, MAX_EXPRESSION_LENGTH_IN_MESSAGE) + "|" + expression.length() + "|" + expression.hashCode();
  }

  /** Marks the expression on this thread; returns the previous one for {@link #exitExpression(String)}. */
  static String enterExpression(String expression) {
    String previous = CURRENT_EXPRESSION.get();
    CURRENT_EXPRESSION.set(expression);
    return previous;
  }

  static void exitExpression(String previous) {
    if (previous == null) {
      CURRENT_EXPRESSION.remove();
    } else {
      CURRENT_EXPRESSION.set(previous);
    }
  }

  private String findNameEntry(String name, boolean bean) {
    if (literalNames.contains(name)) {
      return name;
    }
    if (bean && !name.isEmpty() && !getterEntriesByProperty.isEmpty()) {
      String entry = getterEntriesByProperty.get(lowerFirst(name));
      if (entry != null) {
        return entry;
      }
    }
    for (Rule rule : wildcardNames) {
      if (rule.matches(name)
          || (bean && !name.isEmpty() && (rule.matches("get" + upperFirst(name)) || rule.matches("is" + upperFirst(name))))) {
        return rule.entry;
      }
    }
    return null;
  }

  private String findMemberEntry(Class<?> type, String name, boolean bean) {
    for (MemberRule rule : memberRules) {
      boolean nameMatches = rule.name.matches(name)
          || (bean && !name.isEmpty() && (rule.name.matches("get" + upperFirst(name)) || rule.name.matches("is" + upperFirst(name))));
      if (nameMatches && findTypeEntry(type, Collections.singletonList(rule.type)) != null) {
        return rule.entry;
      }
    }
    return null;
  }

  /** A value counts as its type; a Class also as the type it represents, a member as its declaring type. */
  private String findValueTypeEntry(Object value) {
    String entry = findTypeEntry(value.getClass());
    if (entry == null && value instanceof Class) {
      entry = findTypeEntry((Class<?>) value);
    }
    if (entry == null && value instanceof Member) {
      entry = findTypeEntry(((Member) value).getDeclaringClass());
    }
    return entry;
  }

  private String findTypeEntry(Class<?> type) {
    String entry = deniedTypeEntry.get(type);
    return entry.isEmpty() ? null : entry;
  }

  /** The first matching entry for the type, its component type, supertypes or interfaces. */
  private static String findTypeEntry(Class<?> type, List<Rule> rules) {
    while (type.isArray()) {
      type = type.getComponentType();
    }
    if (Proxy.isProxyClass(type)) {
      // a JDK proxy counts as its interfaces
      return findTypeEntryIncludingInterfaces(type, rules);
    }
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      String entry = findTypeEntryIncludingInterfaces(current, rules);
      if (entry != null) {
        return entry;
      }
    }
    return null;
  }

  private static String findTypeEntryOfClass(Class<?> type, List<Rule> rules) {
    for (Rule rule : rules) {
      if (rule.matches(type.getName())) {
        return rule.entry;
      }
    }
    return null;
  }

  private static String findTypeEntryIncludingInterfaces(Class<?> type, List<Rule> rules) {
    for (Rule rule : rules) {
      if (rule.matches(type.getName())) {
        return rule.entry;
      }
    }
    for (Class<?> anInterface : type.getInterfaces()) {
      String entry = findTypeEntryIncludingInterfaces(anInterface, rules);
      if (entry != null) {
        return entry;
      }
    }
    return null;
  }

  /** The property of a getter entry ({@code class} for {@code getClass}), else null. */
  private static String getterProperty(String entry) {
    if (entry.length() > 3 && entry.startsWith("get") && Character.isUpperCase(entry.charAt(3))) {
      return lowerFirst(entry.substring(3));
    }
    if (entry.length() > 2 && entry.startsWith("is") && Character.isUpperCase(entry.charAt(2))) {
      return lowerFirst(entry.substring(2));
    }
    return null;
  }

  // a property's toString() may return null
  private static String nameOf(Object property) {
    String name = property.toString();
    return name != null ? name : "null";
  }

  private static String lowerFirst(String name) {
    char first = name.charAt(0);
    return Character.isUpperCase(first) ? Character.toLowerCase(first) + name.substring(1) : name;
  }

  private static String upperFirst(String name) {
    char first = name.charAt(0);
    return Character.isUpperCase(first) ? name : Character.toUpperCase(first) + name.substring(1);
  }

  // names can come from data: bounded, no characters that break log lines
  private static String sanitize(String name) {
    return sanitize(name, MAX_NAME_LENGTH_IN_MESSAGE);
  }

  private static String sanitize(String text, int maxLength) {
    StringBuilder result = new StringBuilder(Math.min(text.length(), maxLength));
    for (int i = 0; i < text.length() && result.length() < maxLength; i++) {
      char c = text.charAt(i);
      result.append(isUnsafeInLog(c) ? '?' : c);
    }
    if (text.length() > maxLength) {
      result.append("...");
    }
    return result.toString();
  }

  // controls, line/paragraph separators, format characters such as bidi overrides
  private static boolean isUnsafeInLog(char c) {
    int type = Character.getType(c);
    return Character.isISOControl(c)
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR
        || type == Character.FORMAT;
  }

  private static Pattern namePattern(String entry) {
    // DOTALL: a line break must not slip past a wildcard
    return Pattern.compile(wildcardRegex(entry), Pattern.DOTALL);
  }

  // also nested types and subpackages
  private static Pattern typePattern(String entry) {
    return Pattern.compile(wildcardRegex(entry) + "(?:[.$].*)?", Pattern.DOTALL);
  }

  private static String wildcardRegex(String entry) {
    StringBuilder regex = new StringBuilder();
    int start = 0;
    for (int star = entry.indexOf('*'); star >= 0; star = entry.indexOf('*', start)) {
      if (star > start) {
        regex.append(Pattern.quote(entry.substring(start, star)));
      }
      regex.append(".*");
      start = star + 1;
    }
    if (start < entry.length()) {
      regex.append(Pattern.quote(entry.substring(start)));
    }
    return regex.toString();
  }

  @Override
  public String toString() {
    return "ExpressionAccessPolicy[mode=" + mode + ", deniedExpressionAccess=" + String.join(",", deniedEntries) + "]";
  }

  private static class Rule {
    final String entry;
    final Pattern pattern;

    Rule(String entry, Pattern pattern) {
      this.entry = entry;
      this.pattern = pattern;
    }

    boolean matches(String value) {
      return pattern.matcher(value).matches();
    }
  }

  private static class MemberRule {
    final String entry;
    final Rule type;
    final Rule name;

    MemberRule(String entry, Pattern type, Pattern name) {
      this.entry = entry;
      this.type = new Rule(entry, type);
      this.name = new Rule(entry, name);
    }
  }
}
