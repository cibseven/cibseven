/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.engine.impl.scripting.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

/**
 * Factory to create {@link JuelScriptEngine}s.
 * 
 * @author Frederik Heremans
 */
public class JuelScriptEngineFactory implements ScriptEngineFactory {

  public static List<String> names;
  private static List<String> extensions;
  private static List<String> mimeTypes;

  static {
    names = Collections.unmodifiableList(Arrays.asList("juel"));
    extensions = names;
    mimeTypes = Collections.unmodifiableList(new ArrayList<String>(0));
  }

  public String getEngineName() {
    return "juel";
  }

  public String getEngineVersion() {
    return "1.0";
  }

  public List<String> getExtensions() {
    return extensions;
  }

  public String getLanguageName() {
    return "JSP 2.1 EL";
  }

  public String getLanguageVersion() {
    return "2.1";
  }

  public String getMethodCallSyntax(String obj, String method, String... arguments) {
    throw new UnsupportedOperationException("Method getMethodCallSyntax is not supported");
  }

  public List<String> getMimeTypes() {
    return mimeTypes;
  }

  public List<String> getNames() {
    return names;
  }

  public String getOutputStatement(String toDisplay) {
    // We will use out:print function to output statements
    StringBuilder stringBuffer = new StringBuilder();
    stringBuffer.append("out:print(\"");
    
    int length = toDisplay.length();
    for (int i = 0; i < length; i++) {
      char c = toDisplay.charAt(i);
      switch (c) {
      case '"':
        stringBuffer.append("\\\"");
        break;
      case '\\':
        stringBuffer.append("\\\\");
        break;
      default:
        stringBuffer.append(c);
        break;
      }
    }
    stringBuffer.append("\")");
    return stringBuffer.toString();
  }

  public String getParameter(String key) {
    if (key.equals(ScriptEngine.NAME)) {
      return getLanguageName();
    } else if (key.equals(ScriptEngine.ENGINE)) {
      return getEngineName();
    } else if (key.equals(ScriptEngine.ENGINE_VERSION)) {
      return getEngineVersion();
    } else if (key.equals(ScriptEngine.LANGUAGE)) {
      return getLanguageName();
    } else if (key.equals(ScriptEngine.LANGUAGE_VERSION)) {
      return getLanguageVersion();
    } else if (key.equals("THREADING")) {
      return "MULTITHREADED";
    } else {
      return null;
    }
  }

  public String getProgram(String... statements) {
    // Each statement is wrapped in '${}' to comply with EL
    StringBuilder buf = new StringBuilder();
    if (statements.length != 0) {
      for (int i = 0; i < statements.length; i++) {
        buf.append("${");
        buf.append(statements[i]);
        buf.append("} ");
      }
    }
    return buf.toString();
  }

  public ScriptEngine getScriptEngine() {
    return new JuelScriptEngine(this);
  }

}
