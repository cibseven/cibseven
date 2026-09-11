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
package org.cibseven.bpm.qa.performance.engine.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.query.Query;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.qa.performance.engine.framework.PerfTestRunContext;
import org.cibseven.bpm.qa.performance.engine.framework.PerfTestStepBehavior;
import org.cibseven.bpm.qa.performance.engine.junit.AuthorizationPerformanceTestCase;
import org.cibseven.bpm.qa.performance.engine.junit.PerfTestProcessEngine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.cibseven.bpm.engine.authorization.Permissions.READ;
import static org.cibseven.bpm.engine.authorization.Resources.*;

/**
 * @author Daniel Meyer
 *
 */
@SuppressWarnings("rawtypes")
public class RuntimeAuthorizationQueryPerformanceTest extends AuthorizationPerformanceTestCase {

  static List<Object[]> queryResourcesAndPermissions;
  static List<Authentication> authentications;

  static {
    ProcessEngine processEngine = PerfTestProcessEngine.getInstance();
    RuntimeService runtimeService = processEngine.getRuntimeService();
    TaskService taskService = processEngine.getTaskService();

    queryResourcesAndPermissions = Arrays.<Object[]>asList(
        new Object[] {
            "ProcessInstanceQuery",
            runtimeService.createProcessInstanceQuery(),
            PROCESS_INSTANCE,
            new Permission[] { READ }
        },
        new Object[] {
            "VariableInstanceQuery",
            runtimeService.createVariableInstanceQuery(),
            PROCESS_INSTANCE,
            new Permission[] { READ }
        },
        new Object[] {
            "TaskQuery",
            taskService.createTaskQuery(),
            TASK,
            new Permission[] { READ }
        }
    );

    authentications = Arrays.asList(
        new Authentication(null, Collections.<String>emptyList()){
          @Override
          public String toString() {
            return "without authentication";
          }
        },
        new Authentication("test", Collections.<String>emptyList()){
          @Override
          public String toString() {
            return "with authenticated user without groups";
          }
        },
        new Authentication("test", Arrays.asList("g0", "g1")) {
          @Override
          public String toString() {
            return "with authenticated user and 2 groups";
          }
        },
        new Authentication("test", Arrays.asList("g0", "g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8", "g9")) {
          @Override
          public String toString() {
            return "with authenticated user and 10 groups";
          }
        }
    );

  }

  static List<Object[]> params() {
    final ArrayList<Object[]> params = new ArrayList<>();
    for (Object[] queryResourcesAndPermission : queryResourcesAndPermissions) {
      for (Authentication authentication : authentications) {
        Object[] array = new Object[queryResourcesAndPermission.length + 1];
        System.arraycopy(queryResourcesAndPermission, 0, array, 0, queryResourcesAndPermission.length);
        array[queryResourcesAndPermission.length] = authentication;
        params.add(array);
      }
    }
    return params;
  }

  private void createAuthorizations(Resource resource, Permission[] permissions) {
    AuthorizationService authorizationService = engine.getAuthorizationService();
    List<Authorization> auths = authorizationService.createAuthorizationQuery().list();
    for (Authorization authorization : auths) {
      authorizationService.deleteAuthorization(authorization.getId());
    }
    userGrant("test", resource, permissions);
    for (int i = 0; i < 5; i++) {
      grouptGrant("g"+i, resource, permissions);
    }
    engine.getProcessEngineConfiguration().setAuthorizationEnabled(true);
  }

  @ParameterizedTest
  @MethodSource("params")
  public void queryList(String name, Query query, Resource resource, Permission[] permissions, Authentication authentication) {
    createAuthorizations(resource, permissions);
    performanceTest().step(new PerfTestStepBehavior() {
      public void execute(PerfTestRunContext context) {
        try {
          engine.getIdentityService().setAuthentication(authentication);
          query.listPage(0, 15);
        } finally {
          engine.getIdentityService().clearAuthentication();
        }
      }
    }).run();
  }

  @ParameterizedTest
  @MethodSource("params")
  public void queryCount(String name, Query query, Resource resource, Permission[] permissions, Authentication authentication) {
    createAuthorizations(resource, permissions);
    performanceTest().step(new PerfTestStepBehavior() {
      public void execute(PerfTestRunContext context) {
        try {
          engine.getIdentityService().setAuthentication(authentication);
          query.count();
        } finally {
          engine.getIdentityService().clearAuthentication();
        }
      }
    }).run();
  }

}