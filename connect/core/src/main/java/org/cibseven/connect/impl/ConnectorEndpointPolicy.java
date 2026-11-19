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
package org.cibseven.connect.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cibseven.connect.ConnectorEndpointNotAllowedException;

/**
 * Opt-in allow-list of the endpoints a connector may contact, so that targets taken
 * from process variables cannot redirect requests and their credentials.
 *
 * <p>First set wins: {@code cibseven.connect.<connectorId>.allowed-endpoints},
 * {@code CIBSEVEN_CONNECT_<CONNECTOR_ID>_ALLOWED_ENDPOINTS}, {@value #GLOBAL_SYSTEM_PROPERTY},
 * {@value #GLOBAL_ENVIRONMENT_VARIABLE}. A connector's own value replaces the global one;
 * {@value #UNRESTRICTED} exempts it. Entries are {@code scheme://[*.]host[:port]}, compared
 * without DNS resolution; invalid entries are ignored with a warning.
 */
public final class ConnectorEndpointPolicy {

  protected static final ConnectCoreLogger LOG = ConnectLogger.CORE_LOGGER;

  /** Test seam for environment variables. */
  static Function<String, String> envReader = System::getenv;

  private static final ConcurrentMap<String, ConnectorEndpointPolicy> POLICIES = new ConcurrentHashMap<>();

  private static final Pattern ENTRY_SEPARATOR = Pattern.compile("[,\\s]+");

  private static final Pattern WILDCARD_ENTRY = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://)\\*\\.(.+)$");

  /** Host name or IPv4 address; excludes {@code ,}, {@code /}, {@code @} and the like. */
  private static final String HOST_NAME = "(?:[A-Za-z0-9_-]+\\.)*[A-Za-z0-9_-]+";

  private static final Pattern VALID_HOST_NAME = Pattern.compile(HOST_NAME);

  /** IPv6 address without brackets and zone id. */
  private static final Pattern VALID_IPV6 = Pattern.compile(
      "(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{0,4}|[0-9]{1,3}(?:\\.[0-9]{1,3}){3})");

  private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");

  /** Authority {@link URI} rejects but HttpClient accepts, e.g. {@code mcp_server:8080}; no user info. */
  private static final Pattern PLAIN_AUTHORITY = Pattern.compile("^(" + HOST_NAME + "\\.?)(?::([0-9]{1,5}))?$");

  private static final String INVALID_ENDPOINT = "(invalid endpoint)";

  public static final String GLOBAL_SYSTEM_PROPERTY = "cibseven.connect.allowed-endpoints";

  public static final String GLOBAL_ENVIRONMENT_VARIABLE = "CIBSEVEN_CONNECT_ALLOWED_ENDPOINTS";

  /** Exempts a connector from the global list. */
  public static final String UNRESTRICTED = "*";

  private final String connectorId;

  private final String configuredValue;

  private final String source;

  private final List<AllowedEndpoint> allowedEndpoints;

  private ConnectorEndpointPolicy(String connectorId, String configuredValue, String source,
      List<AllowedEndpoint> allowedEndpoints) {
    this.connectorId = connectorId;
    this.configuredValue = configuredValue;
    this.source = source;
    this.allowedEndpoints = allowedEndpoints;
  }

  public static ConnectorEndpointPolicy forConnector(String connectorId) {
    Objects.requireNonNull(connectorId, "connectorId");
    String[] sources = {systemPropertyName(connectorId), environmentVariableName(connectorId),
        GLOBAL_SYSTEM_PROPERTY, GLOBAL_ENVIRONMENT_VARIABLE};
    String source = null;
    String value = null;
    for (int i = 0; i < sources.length && value == null; i++) {
      source = sources[i];
      value = trimToNull(i % 2 == 0 ? System.getProperty(source) : envReader.apply(source));
    }
    String configuredValue = value;
    String configuredSource = value == null ? null : source;
    ConnectorEndpointPolicy current = POLICIES.get(connectorId);
    if (current != null && current.isConfiguredBy(configuredValue, configuredSource)) {
      return current;
    }
    return POLICIES.compute(connectorId, (id, existing) ->
        existing != null && existing.isConfiguredBy(configuredValue, configuredSource)
            ? existing
            : parse(id, configuredValue, configuredSource));
  }

  private boolean isConfiguredBy(String value, String valueSource) {
    return Objects.equals(configuredValue, value) && Objects.equals(source, valueSource);
  }

  public static String systemPropertyName(String connectorId) {
    return "cibseven.connect." + connectorId + ".allowed-endpoints";
  }

  public static String environmentVariableName(String connectorId) {
    return "CIBSEVEN_CONNECT_"
        + connectorId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
        + "_ALLOWED_ENDPOINTS";
  }

  public String getConnectorId() {
    return connectorId;
  }

  public boolean isRestricted() {
    return configuredValue != null && !UNRESTRICTED.equals(configuredValue);
  }

  /** Throws {@link ConnectorEndpointNotAllowedException} if restricted and the URL is not allowed. */
  public void checkUrl(String url) {
    if (!isRestricted()) {
      return;
    }
    URI uri = null;
    if (url != null) {
      try {
        uri = new URI(url.trim());
      } catch (URISyntaxException e) {
        // refused below
      }
    }
    checkUri(uri);
  }

  public void checkUri(URI uri) {
    if (!isRestricted()) {
      return;
    }
    HostAndPort authority = uri == null ? null : HostAndPort.of(uri);
    if (authority == null || uri.getScheme() == null) {
      throw refuse(INVALID_ENDPOINT);
    }
    checkEndpoint(uri.getScheme(), authority.host, authority.port);
  }

  /** For endpoints without URL, e.g. a database host; port {@code -1} means the scheme's default. */
  public void checkEndpoint(String scheme, String host, int port) {
    if (!isRestricted()) {
      return;
    }
    if (scheme == null || host == null || !isValidPort(port)) {
      throw refuse(INVALID_ENDPOINT);
    }
    String normalizedHost = normalizeHost(host);
    // else "attacker.test,db.example.com" would match "*.example.com"
    if (!isValidHost(normalizedHost)) {
      throw refuse(INVALID_ENDPOINT);
    }
    String normalizedScheme = normalizeScheme(scheme);
    int effectivePort = effectivePort(normalizedScheme, port);
    for (AllowedEndpoint allowed : allowedEndpoints) {
      if (allowed.matches(normalizedScheme, normalizedHost, effectivePort)) {
        return;
      }
    }
    String shownHost = normalizedHost.indexOf(':') >= 0 ? "[" + normalizedHost + "]" : normalizedHost;
    throw refuse(normalizedScheme + "://" + shownHost + ":" + effectivePort);
  }

  private ConnectorEndpointNotAllowedException refuse(String endpoint) {
    return LOG.endpointNotAllowed(connectorId, endpoint, source,
        systemPropertyName(connectorId), environmentVariableName(connectorId));
  }

  private static ConnectorEndpointPolicy parse(String connectorId, String value, String source) {
    if (value == null) {
      return new ConnectorEndpointPolicy(connectorId, null, null, Collections.emptyList());
    }
    if (UNRESTRICTED.equals(value)) {
      LOG.endpointRestrictionLifted(connectorId, source);
      return new ConnectorEndpointPolicy(connectorId, value, source, Collections.emptyList());
    }
    List<AllowedEndpoint> allowed = new ArrayList<>();
    int position = 0;
    for (String entry : ENTRY_SEPARATOR.split(value)) {
      if (entry.isEmpty()) {
        continue;
      }
      position++;
      AllowedEndpoint endpoint = AllowedEndpoint.parse(entry);
      if (endpoint == null) {
        LOG.invalidAllowedEndpoint(connectorId, position, source);
      } else {
        allowed.add(endpoint);
      }
    }
    if (allowed.isEmpty()) {
      LOG.noAllowedEndpoint(connectorId, source);
    } else {
      LOG.endpointRestrictionActive(connectorId, allowed.size(), source);
    }
    return new ConnectorEndpointPolicy(connectorId, value, source, Collections.unmodifiableList(allowed));
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeHost(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.length() > 2 && normalized.startsWith("[") && normalized.endsWith("]")) {
      return normalized.substring(1, normalized.length() - 1);
    }
    return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private static boolean isValidHost(String normalizedHost) {
    return VALID_HOST_NAME.matcher(normalizedHost).matches()
        || VALID_IPV6.matcher(normalizedHost).matches();
  }

  private static boolean isValidPort(int port) {
    return port == -1 || (port >= 1 && port <= 65535);
  }

  private static final class HostAndPort {

    private final String host;
    private final int port;

    private HostAndPort(String host, int port) {
      this.host = host;
      this.port = port;
    }

    static HostAndPort of(URI uri) {
      if (uri.getHost() != null) {
        return new HostAndPort(uri.getHost(), uri.getPort());
      }
      String authority = uri.getRawAuthority();
      Matcher plain = authority == null ? null : PLAIN_AUTHORITY.matcher(authority);
      if (plain == null || !plain.matches()) {
        return null;
      }
      int port = plain.group(2) == null ? -1 : Integer.parseInt(plain.group(2));
      return new HostAndPort(plain.group(1), port);
    }
  }

  private static String normalizeScheme(String scheme) {
    String normalized = scheme.toLowerCase(Locale.ROOT);
    return "postgres".equals(normalized) ? "postgresql" : normalized;
  }

  private static int effectivePort(String scheme, int port) {
    if (port != -1) {
      return port;
    }
    switch (scheme) {
      case "http":
      case "ws":
        return 80;
      case "https":
      case "wss":
        return 443;
      case "postgresql":
        return 5432;
      default:
        return -1;
    }
  }

  private static final class AllowedEndpoint {

    private final String scheme;
    private final String host;
    private final boolean subdomains;
    private final int port;

    private AllowedEndpoint(String scheme, String host, boolean subdomains, int port) {
      this.scheme = scheme;
      this.host = host;
      this.subdomains = subdomains;
      this.port = port;
    }

    /** {@code null} if invalid. */
    static AllowedEndpoint parse(String entry) {
      String candidate = entry;
      boolean subdomains = false;
      Matcher wildcard = WILDCARD_ENTRY.matcher(entry);
      if (wildcard.matches()) {
        subdomains = true;
        candidate = wildcard.group(1) + wildcard.group(2);
      }
      URI uri;
      try {
        uri = new URI(candidate);
      } catch (URISyntaxException e) {
        return null;
      }
      String path = uri.getRawPath();
      HostAndPort authority = HostAndPort.of(uri);
      if (uri.getScheme() == null || authority == null || !isValidPort(authority.port)
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
          || (path != null && !path.isEmpty() && !"/".equals(path))) {
        return null;
      }
      String host = normalizeHost(authority.host);
      // no *.com or *.0.0.1
      if (subdomains && (host.indexOf('.') < 0 || NUMERIC_HOST.matcher(host).matches()
          || VALID_IPV6.matcher(host).matches())) {
        return null;
      }
      String scheme = normalizeScheme(uri.getScheme());
      int port = effectivePort(scheme, authority.port);
      if (port == -1) {
        return null;
      }
      return new AllowedEndpoint(scheme, host, subdomains, port);
    }

    boolean matches(String scheme, String host, int port) {
      if (!this.scheme.equals(scheme) || this.port != port) {
        return false;
      }
      if (!subdomains) {
        return this.host.equals(host);
      }
      return VALID_HOST_NAME.matcher(host).matches() && host.endsWith("." + this.host);
    }
  }

}
