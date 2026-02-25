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
package org.cibseven.bpm.engine.rest.util.container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.core.Application;

import org.cibseven.bpm.engine.rest.CustomJacksonDateFormatTest;
import org.cibseven.bpm.engine.rest.ExceptionHandlerTest;
import org.cibseven.bpm.engine.rest.application.TestCustomResourceApplication;
import org.cibseven.bpm.engine.rest.standalone.NoServletAuthenticationFilterTest;
import org.cibseven.bpm.engine.rest.standalone.NoServletEmptyBodyFilterTest;
import org.cibseven.bpm.engine.rest.standalone.ServletAuthenticationFilterTest;
import org.cibseven.bpm.engine.rest.standalone.ServletEmptyBodyFilterTest;
import org.junit.jupiter.api.extension.Extension;

/**
 * @author Thorben Lindhauer
 *
 */
public class ResteasySpecifics implements ContainerSpecifics {

  protected static final TestRuleFactory DEFAULT_RULE_FACTORY =
      new EmbeddedServerRuleFactory(new JaxrsApplication());

  protected static final Map<Class<?>, TestRuleFactory> TEST_RULE_FACTORIES =
      new HashMap<Class<?>, TestRuleFactory>();

  static {
    TEST_RULE_FACTORIES.put(ExceptionHandlerTest.class, new EmbeddedServerRuleFactory(new TestCustomResourceApplication()));
    TEST_RULE_FACTORIES.put(ServletAuthenticationFilterTest.class, new ServletContainerRuleFactory("auth-filter-servlet-web.xml"));
    TEST_RULE_FACTORIES.put(NoServletAuthenticationFilterTest.class, new ServletContainerRuleFactory("auth-filter-no-servlet-web.xml"));
    TEST_RULE_FACTORIES.put(ServletEmptyBodyFilterTest.class, new ServletContainerRuleFactory("empty-body-filter-servlet-web.xml"));
    TEST_RULE_FACTORIES.put(NoServletEmptyBodyFilterTest.class, new ServletContainerRuleFactory("empty-body-filter-no-servlet-web.xml"));
    TEST_RULE_FACTORIES.put(CustomJacksonDateFormatTest.class, new ServletContainerRuleFactory("custom-date-format-web.xml"));
  }

  public Extension getTestRule(Class<?> testClass) {
    TestRuleFactory ruleFactory = DEFAULT_RULE_FACTORY;

    if (TEST_RULE_FACTORIES.containsKey(testClass)) {
      ruleFactory = TEST_RULE_FACTORIES.get(testClass);
    }

    return ruleFactory.createTestRule();
  }

  public static class EmbeddedServerRuleFactory implements TestRuleFactory {

    protected Application jaxRsApplication;

    public EmbeddedServerRuleFactory(Application jaxRsApplication) {
      this.jaxRsApplication = jaxRsApplication;
    }

    public Extension createTestRule() {
      return new Extension() {
        ResteasyServerBootstrap bootstrap = new ResteasyServerBootstrap(jaxRsApplication);
        // Use BeforeAllCallback/AfterAllCallback for server lifecycle
        public void beforeAll(ExtensionContext context) throws Exception {
          bootstrap.start();
        }
        public void afterAll(ExtensionContext context) throws Exception {
          bootstrap.stop();
        }
      };
    }
  }

  public static class ServletContainerRuleFactory implements TestRuleFactory {

    protected String webXmlResource;

    public ServletContainerRuleFactory(String webXmlResource) {
      this.webXmlResource = webXmlResource;
    }

    public Extension createTestRule() {
      return new Extension() {
        Path tempDir;
        ResteasyTomcatServerBootstrap bootstrap;
        // Use BeforeAllCallback/AfterAllCallback for server lifecycle
        public void beforeAll(ExtensionContext context) throws Exception {
          tempDir = Files.createTempDirectory("resteasy-tomcat-test");
          bootstrap = new ResteasyTomcatServerBootstrap(webXmlResource);
          bootstrap.setWorkingDir(tempDir.toFile().getAbsolutePath());
          bootstrap.start();
        }
        public void afterAll(ExtensionContext context) throws Exception {
          if (bootstrap != null) {
            bootstrap.stop();
          }
          if (tempDir != null) {
            try { Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete); } catch (IOException ignored) {}
          }
        }
      };
    }
  }

}