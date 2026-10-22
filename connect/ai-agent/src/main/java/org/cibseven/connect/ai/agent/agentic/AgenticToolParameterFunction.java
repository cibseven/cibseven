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
package org.cibseven.connect.ai.agent.agentic;

import java.util.regex.Pattern;

/**
 * How the catalogue reads the expression function {@code toolParameter} (CIB7-2136), with which a
 * tool declares a parameter where it uses it, in an input mapping:
 *
 * <pre>
 * &lt;camunda:inputParameter name="proposal"&gt;
 *   ${toolParameter(toolCall.amount, 'Amount in euros', 'integer')}
 * &lt;/camunda:inputParameter&gt;
 * </pre>
 *
 * <p>The description and the type may be left out: {@code toolParameter(toolCall.amount)} is a
 * string without a description, {@code toolParameter(toolCall.amount, 'Amount in euros')} a
 * string with one.
 *
 * <p>A parameter is required (CIB7-2135): the model must supply it, and a call that leaves it out,
 * or passes null, is refused before the activity starts, so the model calls again with it. A
 * trailing {@code ?} on the type marks it optional, as in
 * {@code toolParameter(toolCall.limit, 'Maximum number of hits', 'integer?')}: the model may leave
 * it out, and the parameter then reads null. A call without a type takes the marker with the type
 * another call of the same parameter gives.
 *
 * <p>Two readers of the same text. At deployment the catalogue parser reads the call, its
 * parameter name after {@code toolCall.}, and its literal description and type, and offers the
 * tool with that parameter; only a call in an input parameter of an activity the agent starts,
 * its own or its connector's, declares one, and a call anywhere else in the scope is refused. At
 * run time the expression is evaluated as any other: EL resolves {@code toolCall.amount} from the
 * map the agent's call puts its arguments in, and the function returns that value. A tool started
 * by something other than the agent finds the empty map the scope keeps, and the parameter reads
 * null. Where a toolCall is visible at the scope already, the scope keeps no map of its own and
 * such a tool reads that one: the outer call's, when the scope is itself the tool of an outer
 * agent.
 *
 * <p>The function itself is registered by the connect plugin, as
 * {@code org.cibseven.connect.plugin.impl.ToolParameterFunction}, not by this module: an
 * expression binds its functions when the model is parsed, and a model using it has to parse while
 * the AI agent is switched off too, for a definition deployed before as well.
 */
public final class AgenticToolParameterFunction {

  /** The function's name in expressions; the connect plugin registers it under this name. */
  public static final String NAME = "toolParameter";

  /** The words of EL that are no names: before a colon, they are no prefix. */
  private static final String KEYWORDS =
      "(?:null|true|false|empty|not|and|or|div|mod|eq|ne|lt|gt|le|ge|instanceof)";

  /**
   * A prefix before the function's name, as EL reads one: a name, not a keyword and not a
   * property after a dot, spaces around the dot or not, then a colon, spaces allowed around it.
   */
  private static final String PREFIX = "(?<![\\p{javaJavaIdentifierPart}.])(?<!\\.\\s{1,16})"
      + "(?!" + KEYWORDS + "(?!\\p{javaJavaIdentifierPart}))\\p{javaJavaIdentifierStart}"
      + "\\p{javaJavaIdentifierPart}{0,63}\\s{0,16}:\\s{0,16}";

  /**
   * The function's name where it calls the function: not part of a longer name, and neither a
   * method of something, as in {@code settings.toolParameter(...)}, nor a function with a prefix,
   * as in {@code fn:toolParameter(...)}, which are someone else's. The colon of a condition after
   * anything else is no prefix, as in {@code flag ? '' : toolParameter(...)} or
   * {@code flag ? 0:toolParameter(...)}.
   */
  private static final String FREE = "(?<![\\w$])(?<!\\.\\s{0,16})(?<!" + PREFIX + ")" + NAME;

  /** Any call of the function, to find the ones the strict form below does not match. */
  static final Pattern ANY_CALL = Pattern.compile(FREE + "\\s*\\(");

  /**
   * A string literal of EL, in single or double quotes; inside, as EL reads it, a backslash
   * escapes a backslash or the quote that encloses the literal ({@link #unescape}).
   */
  private static final String LITERAL =
      "(?:'((?:[^'\\\\]|\\\\[\\\\'])*+)'|\"((?:[^\"\\\\]|\\\\[\\\\\"])*+)\")";

  private static final Pattern ESCAPE = Pattern.compile("\\\\([\\\\'\"])");

  /** The type of a parameter whose declaration leaves it out. */
  static final String DEFAULT_TYPE = "string";

  /**
   * The one form the catalogue reads: the value as {@code toolCall.<name>}, then optionally a
   * literal description and after it a literal type, in single or double quotes, escaped as EL
   * escapes them.
   */
  static final Pattern DECLARATION = Pattern.compile(FREE + "\\s*\\(\\s*"
      + AdHocToolDescriptor.TOOL_CALL_VARIABLE + "\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
      + "(?:,\\s*" + LITERAL + "\\s*(?:,\\s*" + LITERAL + "\\s*)?)?\\)");

  /** The text of a literal {@link #DECLARATION} matched, as EL reads it: its escapes undone. */
  static String unescape(String literal) {
    return (literal == null) ? null : ESCAPE.matcher(literal).replaceAll("$1");
  }

  private AgenticToolParameterFunction() {
    // the reading of the function only
  }
}
