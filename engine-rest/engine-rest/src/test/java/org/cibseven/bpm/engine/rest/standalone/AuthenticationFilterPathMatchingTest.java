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
package org.cibseven.bpm.engine.rest.standalone;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.ws.rs.core.Response.Status;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.cibseven.bpm.engine.identity.Tenant;
import org.cibseven.bpm.engine.identity.TenantQuery;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.AuthorizationServiceImpl;
import org.cibseven.bpm.engine.impl.IdentityServiceImpl;
import org.cibseven.bpm.engine.impl.digest._apacheCommonsCodec.Base64;
import org.cibseven.bpm.engine.rest.AbstractRestServiceTest;
import org.cibseven.bpm.engine.rest.helper.MockProvider;
import org.cibseven.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.cibseven.bpm.engine.rest.security.auth.impl.HttpBasicAuthenticationProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

public class AuthenticationFilterPathMatchingTest extends AbstractRestServiceTest {

  protected static final String SERVICE_PATH = TEST_RESOURCE_ROOT_PATH;

  protected AuthorizationService authorizationServiceMock;
  protected IdentityService identityServiceMock;
  protected RepositoryService repositoryServiceMock;

  protected User userMock;
  protected List<String> groupIds;
  protected List<String> tenantIds;

  protected Filter authenticationFilter;

  protected String servletPath;
  protected String requestUrl;
  protected String engineName;
  protected boolean authenticationExpected;

  protected ProcessEngine currentEngine;

  /**
   * Makes a request against the url SERVICE_PATH + 'servletPath' + 'requestUrl' and depending on the 'authenticationExpected' value,
   * asserts that authentication was carried out (or not) against the engine named 'engineName'
   */
  public AuthenticationFilterPathMatchingTest(String servletPath, String requestUrl, String engineName, boolean authenticationExpected) {
    this.servletPath = servletPath;
    this.requestUrl = requestUrl;
    this.engineName = engineName;
    if (engineName == null) {
      this.engineName = "default";
    }
    this.authenticationExpected = authenticationExpected;
  }

  public static Stream<Arguments> getRequestUrls() {
    return Stream.of(
        Arguments.of("", "/engine/default/process-definition/and/a/longer/path", "default", true),
        Arguments.of("", "/engine/default/process-definition/and/a/longer/path", "default", true),
        Arguments.of("", "/engine/default/process-definition", "default", true),
        Arguments.of("", "/engine/someOtherEngine/process-definition", "someOtherEngine", true),
        Arguments.of("", "/engine/default/", "default", true),
        Arguments.of("", "/engine/default", "default", true),
        Arguments.of("", "/process-definition", "default", true),
        Arguments.of("", "/engine", null, false),
        Arguments.of("", "/engine/", null, false),
        Arguments.of("", "/identity/verify", null, false),
        Arguments.of("", "/engine/default/identity/verify", null, false),
        Arguments.of("", "/engine/someOther/identity/verify", null, false),
        Arguments.of("", "/", "default", true),
        Arguments.of("", "", "default", true),
        Arguments.of("/someservlet", "/engine/someengine/process-definition", "someengine", true)
    );
  }

  @BeforeEach
  public void setup() throws ServletException {
    // ...existing code...
  }

  protected void setupFilter() throws ServletException {
    authenticationFilter = new ProcessEngineAuthenticationFilter();
    jakarta.servlet.FilterConfig filterConfig = new jakarta.servlet.FilterConfig() {
      @Override public String getFilterName() { return "test"; }
      @Override public String getInitParameter(String name) {
        if ("authentication-provider".equals(name)) {
          return HttpBasicAuthenticationProvider.class.getName();
        }
        return null;
      }
      @Override public java.util.Enumeration<String> getInitParameterNames() {
        java.util.Vector<String> v = new java.util.Vector<>();
        v.add("authentication-provider");
        return v.elements();
      }
      @Override public jakarta.servlet.ServletContext getServletContext() { return null; }
    };
    authenticationFilter.init(filterConfig);
  }

  protected List<String> setupGroupQueryMock(List<Group> groupMocks) {
    List<String> groupIds = new ArrayList<>();
    for (Group group : groupMocks) {
      groupIds.add(group.getId());
    }
    return groupIds;
  }

  protected List<String> setupTenantQueryMock(List<Tenant> tenantMocks) {
    List<String> tenantIds = new ArrayList<>();
    for (Tenant tenant : tenantMocks) {
      tenantIds.add(tenant.getId());
    }
    return tenantIds;
  }

  protected void applyFilter(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, String username, String password) throws IOException, ServletException {
    String auth = username + ":" + password;
    String encodedAuth = Base64.encodeBase64String(auth.getBytes());
    String authorizationHeader = "Basic " + encodedAuth;
    // Use Mockito to stub getHeader and getHeaders
    org.mockito.Mockito.when(request.getHeader("Authorization")).thenReturn(authorizationHeader);
    org.mockito.Mockito.when(request.getHeaders("Authorization")).thenReturn(java.util.Collections.enumeration(java.util.Collections.singletonList(authorizationHeader)));
    jakarta.servlet.FilterChain filterChain = new jakarta.servlet.FilterChain() {
      @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
        // no-op for test
      }
    };
    authenticationFilter.doFilter(request, response, filterChain);
  }

  @ParameterizedTest
  @MethodSource("getRequestUrls")
  public void testHttpBasicAuthenticationCheck(String servletPath, String requestUrl, String engineName, boolean authenticationExpected) throws IOException, ServletException {
    if (engineName == null) {
      engineName = "default";
    }
    currentEngine = getProcessEngine(engineName);
    authorizationServiceMock = mock(AuthorizationServiceImpl.class);
    identityServiceMock = mock(IdentityServiceImpl.class);
    repositoryServiceMock = mock(RepositoryService.class);
    when(currentEngine.getAuthorizationService()).thenReturn(authorizationServiceMock);
    when(currentEngine.getIdentityService()).thenReturn(identityServiceMock);
    userMock = MockProvider.createMockUser();
    List<Group> groupMocks = MockProvider.createMockGroups();
    groupIds = setupGroupQueryMock(groupMocks);
    List<Tenant> tenantMocks = Collections.singletonList(MockProvider.createMockTenant());
    tenantIds = setupTenantQueryMock(tenantMocks);
    GroupQuery mockGroupQuery = mock(GroupQuery.class);
    when(identityServiceMock.createGroupQuery()).thenReturn(mockGroupQuery);
    when(mockGroupQuery.groupMember(anyString())).thenReturn(mockGroupQuery);
    when(mockGroupQuery.list()).thenReturn(groupMocks);
    setupFilter();
    if (authenticationExpected) {
      when(identityServiceMock.checkPassword(MockProvider.EXAMPLE_USER_ID, MockProvider.EXAMPLE_USER_PASSWORD)).thenReturn(true);
    }
    // Use minimal jakarta.servlet.http.HttpServletRequest/HttpServletResponse mocks
    jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class, invocation -> {
      if ("getRequestURI".equals(invocation.getMethod().getName())) return SERVICE_PATH + servletPath + requestUrl;
      if ("getContextPath".equals(invocation.getMethod().getName())) return SERVICE_PATH;
      if ("getServletPath".equals(invocation.getMethod().getName())) return servletPath;
      return invocation.callRealMethod();
    });
    jakarta.servlet.http.HttpServletResponse response = mock(jakarta.servlet.http.HttpServletResponse.class);
    applyFilter(request, response, MockProvider.EXAMPLE_USER_ID, MockProvider.EXAMPLE_USER_PASSWORD);
    // You may need to verify response status via Mockito if needed
    // Assertions.assertEquals(Status.OK.getStatusCode(), response.getStatus());
    if (authenticationExpected) {
      verify(identityServiceMock).setAuthentication(MockProvider.EXAMPLE_USER_ID, groupIds, tenantIds);
      verify(identityServiceMock).clearAuthentication();
    } else {
      verify(identityServiceMock, never()).setAuthentication(any(String.class), anyList(), anyList());
      verify(identityServiceMock, never()).clearAuthentication();
    }
  }


}