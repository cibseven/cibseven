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
package org.cibseven.bpm.spring.boot.starter.webapp.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Injects a script into the pages of the webclient so that logging out also ends the session at
 * the identity provider. Dropping the token kept in the browser is not enough on its own: the
 * provider still recognizes the browser, hands out a new authorization code right away and the
 * user ends up logged straight back in.
 *
 * <p>The script is injected here rather than shipped inside the webclient so that the behaviour
 * can be enabled per installation through configuration alone.
 *
 * <p>It hooks the removal of the session token instead of the logout button, which keeps it
 * independent of the markup and of the translated labels of that button.
 */
public class SsoLogoutInjectionFilter implements Filter {

  protected static final String HEAD_END = "</head>";
  protected static final String MARKER = "cibseven.ssoLogoutRequested";

  protected final String scriptTag;
  protected final String applicationPath;

  public SsoLogoutInjectionFilter(String applicationPath, String endSessionEndpoint, String clientId) {
    this.applicationPath = applicationPath;
    this.scriptTag = buildScriptTag(endSessionEndpoint, clientId);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String path = pathWithinApplication(httpRequest);
    if (!isInjectable(path)) {
      // everything else, the assets above all, is served without being buffered
      httpResponse.setHeader("X-Sso-Logout-Filter", "skip " + path);
      chain.doFilter(request, response);
      return;
    }
    httpResponse.setHeader("X-Sso-Logout-Filter", "inject " + path);

    CapturingResponse captured = new CapturingResponse(httpResponse);

    // the body is rewritten, so the validators of the file on disk no longer describe what is
    // served: hide the conditional headers to always get a body back to inject into
    chain.doFilter(new UnconditionalRequest(httpRequest), captured);

    byte[] body = captured.toByteArray();
    String contentType = captured.getContentType();
    if (contentType == null || !contentType.contains("html")) {
      // not a page we can inject into, pass it through untouched
      httpResponse.getOutputStream().write(body);
      return;
    }

    String page = new String(body, StandardCharsets.UTF_8);
    int headEnd = page.indexOf(HEAD_END);
    if (headEnd >= 0 && !page.contains(MARKER)) {
      page = page.substring(0, headEnd) + scriptTag + page.substring(headEnd);
    }

    byte[] injected = page.getBytes(StandardCharsets.UTF_8);
    // the page carries the injected script, which changes with the configuration, so it must
    // not be kept by the browser across a restart
    httpResponse.setHeader("Cache-Control", "no-store");
    httpResponse.setContentLength(injected.length);
    httpResponse.getOutputStream().write(injected);
  }

  /**
   * Only the pages themselves are buffered, so the assets are never copied through memory.
   *
   * <p>The entry point of the application carries no name of its own and is deliberately not
   * matched here: it resolves to the index through a forward, and buffering a response across
   * that forward loses the body. The filter is registered for forwards as well, so the index is
   * rewritten when the forward reaches it.
   */
  protected boolean isInjectable(String path) {
    return path.startsWith(applicationPath + "/") && path.endsWith(".html");
  }

  protected String pathWithinApplication(HttpServletRequest request) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    return path;
  }

  /**
   * The script runs on both the application and the sso login page: on the first it records that
   * a logout was requested, on the second it acts on it. Splitting it that way survives the full
   * page reload the application performs as part of its logout.
   */
  protected String buildScriptTag(String endSessionEndpoint, String clientId) {
    return "<script>(function(){"
        + "var e=" + toJsString(endSessionEndpoint) + ",c=" + toJsString(clientId) + ";"
        + "var m='cibseven.ssoLogoutRequested',g='cibseven.ssoLogoutAt';"
        // the application drops its token on logout, but also whenever a session simply
        // expired. Bracketing the click dispatch tells the two apart: the logout runs inside
        // the click, the expiry handling runs later, out of a response callback.
        + "var i=false;"
        + "document.addEventListener('click',function(v){if(v.isTrusted){i=true;"
        + "setTimeout(function(){i=false;},0);}},true);"
        + "document.addEventListener('click',function(){i=false;},false);"
        + "var r=Storage.prototype.removeItem;"
        + "Storage.prototype.removeItem=function(k){"
        + "if(k==='token'&&i){try{window.sessionStorage.setItem(m,'1');}catch(x){}}"
        + "return r.apply(this,arguments);};"
        // the reload that follows the logout lands here, before a new code is requested
        + "if(window.location.pathname.indexOf('sso-login')!==-1){"
        + "var q=null,l=0;try{q=window.sessionStorage.getItem(m);"
        + "l=parseInt(window.sessionStorage.getItem(g)||'0',10);}catch(x){}"
        + "if(q){try{window.sessionStorage.removeItem(m);}catch(x){}"
        // never bounce twice in a row, so a stale marker cannot become a redirect loop
        + "if(Date.now()-l>10000){"
        + "try{window.sessionStorage.setItem(g,String(Date.now()));}catch(x){}"
        // come back to the application root, which is the url registered at the provider
        + "var b=window.location.origin+window.location.pathname.replace(/[^/]*$/,'');"
        + "window.location.href=e+(e.indexOf('?')===-1?'?':'&')"
        + "+'client_id='+encodeURIComponent(c)"
        + "+'&post_logout_redirect_uri='+encodeURIComponent(b);}}}"
        + "})();</script>";
  }

  protected String toJsString(String value) {
    if (value == null) {
      return "''";
    }
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c") + "'";
  }

  /**
   * Hides the conditional headers of a request. Without this the resource handler answers with a
   * "304 Not Modified" and the browser keeps serving the page it cached earlier, script and all,
   * which makes any later change to the injected script invisible.
   */
  protected static class UnconditionalRequest extends HttpServletRequestWrapper {

    protected static final Set<String> HIDDEN = Set.of("if-modified-since", "if-none-match");

    public UnconditionalRequest(HttpServletRequest request) {
      super(request);
    }

    protected boolean isHidden(String name) {
      return name != null && HIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getHeader(String name) {
      return isHidden(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public long getDateHeader(String name) {
      return isHidden(name) ? -1 : super.getDateHeader(name);
    }
  }

  /**
   * Buffers the response so the body can be rewritten before it reaches the client. The static
   * resources of the webclient are written through the output stream, the forwarded index through
   * the writer, so both have to be captured.
   */
  protected static class CapturingResponse extends HttpServletResponseWrapper {

    protected static final Set<String> DROPPED = Set.of("last-modified", "etag", "content-length");

    protected final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    protected ServletOutputStream outputStream;
    protected PrintWriter writer;

    public CapturingResponse(HttpServletResponse response) {
      super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
      if (writer != null) {
        throw new IllegalStateException("getWriter() has already been called on this response");
      }
      if (outputStream == null) {
        outputStream = new ServletOutputStream() {
          @Override
          public void write(int b) {
            buffer.write(b);
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            // buffered in memory, so it is always ready to be written to
          }
        };
      }
      return outputStream;
    }

    @Override
    public PrintWriter getWriter() {
      if (outputStream != null) {
        throw new IllegalStateException("getOutputStream() has already been called on this response");
      }
      if (writer == null) {
        writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
      }
      return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
      if (writer != null) {
        writer.flush();
      }
      // deliberately not flushing the wrapped response: the body still has to be rewritten
    }

    @Override
    public void setContentLength(int len) {
      // the length changes once the script is injected, so the original one is dropped
    }

    @Override
    public void setContentLengthLong(long len) {
      // see setContentLength(int)
    }

    protected boolean isDropped(String name) {
      return name != null && DROPPED.contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void setHeader(String name, String value) {
      // a validator of the original file would let the browser keep a page we have rewritten
      if (!isDropped(name)) {
        super.setHeader(name, value);
      }
    }

    @Override
    public void addHeader(String name, String value) {
      if (!isDropped(name)) {
        super.addHeader(name, value);
      }
    }

    @Override
    public void setDateHeader(String name, long date) {
      if (!isDropped(name)) {
        super.setDateHeader(name, date);
      }
    }

    @Override
    public void addDateHeader(String name, long date) {
      if (!isDropped(name)) {
        super.addDateHeader(name, date);
      }
    }

    public byte[] toByteArray() {
      if (writer != null) {
        writer.flush();
      }
      return buffer.toByteArray();
    }
  }
}
