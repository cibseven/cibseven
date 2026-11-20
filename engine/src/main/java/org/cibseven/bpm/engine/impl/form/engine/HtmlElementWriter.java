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
package org.cibseven.bpm.engine.impl.form.engine;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import org.cibseven.bpm.engine.impl.form.engine.HtmlDocumentBuilder.HtmlWriteContext;

/**
 * <p>Simple writer for html elements. Used by the {@link HtmlDocumentBuilder}.</p>
 *
 * <p>Attribute values passed to {@link #attribute(String, String)} and text passed to
 * {@link #textContent(String)} are HTML-escaped when the element is written, so they
 * can safely carry untrusted input. {@link #rawAttribute(String, String)} and
 * {@link #rawTextContent(String)} write their value verbatim and are reserved for
 * constant strings and for values that are interpreted as code by the client (AngularJS
 * expressions, form scripts) and were validated by the caller; they must never carry
 * unvalidated input.</p>
 *
 * @author Daniel Meyer
 *
 */
public class HtmlElementWriter {

  /** attribute names are written verbatim and are therefore restricted to plain HTML name characters */
  protected static final Pattern ATTRIBUTE_NAME_PATTERN = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.\\-]*");

  protected String tagName;

  /** selfClosing means that the element should not be rendered as a
   * start + end tag pair but as a single tag using "/" to close the tag
   * inline */
  protected boolean isSelfClosing;
  protected String textContent;
  /** true if {@link #textContent} is written without escaping */
  protected boolean isRawTextContent;
  protected Map<String, String> attributes = new LinkedHashMap<String, String>();
  /** names of the attributes whose values are written without escaping */
  protected Set<String> rawAttributes = new HashSet<String>();

  public HtmlElementWriter(String tagName) {
    this.tagName = tagName;
    this.isSelfClosing = false;
  }

  public HtmlElementWriter(String tagName, boolean isSelfClosing) {
    this.tagName = tagName;
    this.isSelfClosing = isSelfClosing;
  }

  public void writeStartTag(HtmlWriteContext context) {
    writeLeadingWhitespace(context);
    writeStartTagOpen(context);
    writeAttributes(context);
    writeStartTagClose(context);
    writeEndLine(context);
  }

  public void writeContent(HtmlWriteContext context) {
    if(textContent != null) {
      writeLeadingWhitespace(context);
      writeTextContent(context);
      writeEndLine(context);
    }
  }

  public void writeEndTag(HtmlWriteContext context) {
    if(!isSelfClosing) {
      writeLeadingWhitespace(context);
      writeEndTagElement(context);
      writeEndLine(context);
    }
  }

  protected void writeEndTagElement(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    writer.write("</");
    writer.write(tagName);
    writer.write(">");
  }

  protected void writeTextContent(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    writer.write("  "); // add additional whitespace
    writer.write(isRawTextContent ? textContent : escapeTextContent(textContent));
  }

  protected void writeStartTagOpen(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    writer.write("<");
    writer.write(tagName);
  }

  protected void writeAttributes(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    for (Entry<String, String> attribute : attributes.entrySet()) {
      writer.write(" ");
      writer.write(attribute.getKey());
      String attributeValue = attribute.getValue();
      if(attributeValue != null) {
        writer.write("=\"");
        if (!rawAttributes.contains(attribute.getKey())) {
          attributeValue = escapeAttributeValue(attributeValue);
        }
        writer.write(attributeValue);
        writer.write("\"");
      }
    }
  }

  /**
   * Escapes a value for use inside a double-quoted HTML attribute value:
   * <code>&amp;</code>, <code>&lt;</code>, <code>&gt;</code>, <code>"</code> and <code>'</code>
   * are replaced by character references.
   */
  protected String escapeAttributeValue(String value) {
    return escapeHtml(value, true);
  }

  /**
   * Escapes a value for use as element text content: <code>&amp;</code>, <code>&lt;</code>
   * and <code>&gt;</code> are replaced by character references. Quotes cannot end a text
   * node and are kept as they are.
   */
  protected String escapeTextContent(String value) {
    return escapeHtml(value, false);
  }

  protected static String escapeHtml(String value, boolean escapeQuotes) {
    StringBuilder escaped = null;
    int length = value.length();
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      String replacement = null;
      switch (c) {
        case '&':
          replacement = "&amp;";
          break;
        case '<':
          replacement = "&lt;";
          break;
        case '>':
          replacement = "&gt;";
          break;
        case '"':
          replacement = escapeQuotes ? "&quot;" : null;
          break;
        case '\'':
          replacement = escapeQuotes ? "&#39;" : null;
          break;
        default:
          break;
      }
      if (replacement != null) {
        if (escaped == null) {
          escaped = new StringBuilder(length + 16);
          escaped.append(value, 0, i);
        }
        escaped.append(replacement);
      } else if (escaped != null) {
        escaped.append(c);
      }
    }
    return escaped == null ? value : escaped.toString();
  }

  /**
   * @deprecated attribute values are escaped by {@link #escapeAttributeValue(String)};
   * this method is no longer called by the writer and is only kept so that existing
   * subclasses continue to compile.
   */
  @Deprecated
  protected String escapeQuotes(String attributeValue){
    String escapedHtmlQuote = "&quot;";
    String escapedJavaQuote = "\"";
    return attributeValue.replaceAll(escapedJavaQuote, escapedHtmlQuote);
  }

  protected void writeEndLine(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    writer.write("\n");
  }

  protected void writeStartTagClose(HtmlWriteContext context) {
    StringWriter writer = context.getWriter();
    if(isSelfClosing) {
      writer.write(" /");
    }
    writer.write(">");
  }

  protected void writeLeadingWhitespace(HtmlWriteContext context) {
    int stackSize = context.getElementStackSize();
    StringWriter writer = context.getWriter();
    for (int i = 0; i < stackSize; i++) {
      writer.write("  ");
    }
  }

  protected void checkAttributeName(String name) {
    if (name == null || !ATTRIBUTE_NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid HTML attribute name '" + name + "'.");
    }
  }

  // builder /////////////////////////////////////

  /**
   * Adds an attribute. The value is HTML-escaped when the element is written;
   * a <code>null</code> value renders the attribute without a value.
   */
  public HtmlElementWriter attribute(String name, String value) {
    checkAttributeName(name);
    attributes.put(name, value);
    rawAttributes.remove(name);
    return this;
  }

  /**
   * Adds an attribute whose value is written verbatim. Only use this for constant
   * values or for values that were validated to be safe in the context in which
   * the client interprets them (e.g. AngularJS expressions built from a validated
   * form field id); never pass unvalidated input.
   */
  public HtmlElementWriter rawAttribute(String name, String value) {
    checkAttributeName(name);
    attributes.put(name, value);
    rawAttributes.add(name);
    return this;
  }

  /**
   * Sets the text content. It is HTML-escaped when the element is written.
   */
  public HtmlElementWriter textContent(String text) {
    if(isSelfClosing) {
      throw new IllegalStateException("Self-closing element cannot have text content.");
    }
    this.textContent = text;
    this.isRawTextContent = false;
    return this;
  }

  /**
   * Sets text content that is written verbatim. Only use this for constant
   * strings or for content that was validated to be safe in its context (e.g. a
   * form script built from a validated form field id); never pass unvalidated input.
   */
  public HtmlElementWriter rawTextContent(String text) {
    if(isSelfClosing) {
      throw new IllegalStateException("Self-closing element cannot have text content.");
    }
    this.textContent = text;
    this.isRawTextContent = true;
    return this;
  }

}
