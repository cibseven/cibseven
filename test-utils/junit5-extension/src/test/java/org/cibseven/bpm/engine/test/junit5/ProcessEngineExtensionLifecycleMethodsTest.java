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
package org.cibseven.bpm.engine.test.junit5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * JUnit Jupiter calls the {@code @BeforeEach} and {@code @AfterEach} methods of
 * a test class itself. The {@link ProcessEngineExtension} must not call them a
 * second time.
 */
public class ProcessEngineExtensionLifecycleMethodsTest {

  private static final String ENABLED =
      "org.cibseven.bpm.engine.test.junit5.ProcessEngineExtensionLifecycleMethodsTest#isEnabled";

  private static final List<String> CALLS = new ArrayList<>();

  private static boolean enabled = false;

  public static boolean isEnabled() {
    return enabled;
  }

  @BeforeAll
  public static void enableTestCases() {
    enabled = true;
  }

  @AfterAll
  public static void disableTestCases() {
    enabled = false;
  }

  @BeforeEach
  public void clearCalls() {
    CALLS.clear();
  }

  @Test
  public void shouldCallLifecycleMethodsOncePerTestWithExtendWith() {
    // when
    executeAndAssertSuccess(ExtendWithTestCase.class);

    // then
    assertThat(CALLS).containsExactly(
        "beforeEach", "test1", "afterEach",
        "beforeEach", "test2", "afterEach");
  }

  @Test
  public void shouldCallLifecycleMethodsOncePerTestWithRegisterExtension() {
    // when
    executeAndAssertSuccess(RegisterExtensionTestCase.class);

    // then
    assertThat(CALLS).containsExactly(
        "beforeEach", "test1", "afterEach",
        "beforeEach", "test2", "afterEach");
  }

  @Test
  public void shouldCallSkippedLifecycleMethodsOncePerTestWhenEnabled() {
    // when
    executeAndAssertSuccess(SkippedLifecycleMethodsTestCase.class);

    // then
    assertThat(CALLS).containsExactly(
        "beforeEach", "test1", "afterEach",
        "beforeEach", "test2", "afterEach");
  }

  @Test
  public void shouldCallInheritedLifecycleMethodsInTheOrderOfJUnitWhenEnabled() {
    // given the order in which JUnit Jupiter itself calls the methods of the class hierarchy
    executeAndAssertSuccess(HierarchyCalledByJUnitTestCase.class);
    List<String> junitOrder = new ArrayList<>(CALLS);
    CALLS.clear();

    // when the extension calls them
    executeAndAssertSuccess(HierarchyCalledByExtensionTestCase.class);

    // then the order is the same, and an overridden method runs once
    assertThat(CALLS).isEqualTo(junitOrder);
    assertThat(junitOrder).hasSize(12).doesNotContain("overriddenBeforeEach (base)");
    // per test: the superclass @BeforeEach first, then the two of the subclass (their order within one class is
    // up to JUnit), the test, then the subclass @AfterEach before the superclass one
    assertThat(junitOrder.get(0)).isEqualTo("baseBeforeEach");
    assertThat(junitOrder.subList(1, 3)).containsExactlyInAnyOrder("overriddenBeforeEach (subclass)", "subBeforeEach");
    assertThat(junitOrder.subList(3, 6)).containsExactly("test1", "subAfterEach", "baseAfterEach");
  }

  @Test
  public void shouldAbortTestWhenAssumptionInBeforeEachFailsAndEnabled() {
    // when
    EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(AssumptionInBeforeEachTestCase.class))
        .execute()
        .testEvents()
        // then: skipped like JUnit Jupiter does it, not failed
        .assertStatistics(stats -> stats.started(2).aborted(2).failed(0).succeeded(0));
    assertThat(CALLS).doesNotContain("test1", "test2");
  }

  @Test
  public void shouldCallAllAfterEachMethodsAndReportTheOriginalFailureWhenEnabled() {
    // when
    EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(FailingAfterEachTestCase.class))
        .execute()
        .testEvents()
        // then
        .assertStatistics(stats -> stats.started(2).failed(2))
        .failed()
        .assertThatEvents()
        .haveExactly(2, finishedWithFailure(instanceOf(IllegalStateException.class), message("afterEach failed")));
    assertThat(CALLS).containsExactlyInAnyOrder(
        "test1", "failingAfterEach", "otherAfterEach",
        "test2", "failingAfterEach", "otherAfterEach");
  }

  protected static void executeAndAssertSuccess(Class<?> testClass) {
    EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(testClass))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.started(2).succeeded(2));
  }

  /*
   * The test cases below are not executed by the regular test suite but by
   * the test methods above.
   */

  @TestMethodOrder(MethodOrderer.MethodName.class)
  public abstract static class RecordingTestCase {

    @BeforeEach
    public void beforeEach() {
      CALLS.add("beforeEach");
    }

    @AfterEach
    public void afterEach() {
      CALLS.add("afterEach");
    }

    @Test
    public void test1() {
      CALLS.add("test1");
    }

    @Test
    public void test2() {
      CALLS.add("test2");
    }
  }

  @EnabledIf(ENABLED)
  @ExtendWith(ProcessEngineExtension.class)
  public static class ExtendWithTestCase extends RecordingTestCase {
  }

  @EnabledIf(ENABLED)
  public static class RegisterExtensionTestCase extends RecordingTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder().build();
  }

  @EnabledIf(ENABLED)
  @ExtendWith(SkipBeforeAndAfterEachMethods.class)
  public static class SkippedLifecycleMethodsTestCase extends RecordingTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder()
        .invokeBeforeAndAfterEachMethods(true)
        .build();
  }

  @TestMethodOrder(MethodOrderer.MethodName.class)
  public abstract static class HierarchyBaseTestCase {

    @BeforeEach
    public void baseBeforeEach() {
      CALLS.add("baseBeforeEach");
    }

    @BeforeEach
    public void overriddenBeforeEach() {
      CALLS.add("overriddenBeforeEach (base)");
    }

    @AfterEach
    public void baseAfterEach() {
      CALLS.add("baseAfterEach");
    }
  }

  public abstract static class HierarchySubTestCase extends HierarchyBaseTestCase {

    @BeforeEach
    @Override
    public void overriddenBeforeEach() {
      CALLS.add("overriddenBeforeEach (subclass)");
    }

    @BeforeEach
    public void subBeforeEach() {
      CALLS.add("subBeforeEach");
    }

    @AfterEach
    public void subAfterEach() {
      CALLS.add("subAfterEach");
    }

    @Test
    public void test1() {
      CALLS.add("test1");
    }

    @Test
    public void test2() {
      CALLS.add("test2");
    }
  }

  @EnabledIf(ENABLED)
  public static class HierarchyCalledByJUnitTestCase extends HierarchySubTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder().build();
  }

  @EnabledIf(ENABLED)
  @ExtendWith(SkipBeforeAndAfterEachMethods.class)
  public static class HierarchyCalledByExtensionTestCase extends HierarchySubTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder()
        .invokeBeforeAndAfterEachMethods(true)
        .build();
  }

  @EnabledIf(ENABLED)
  @ExtendWith(SkipBeforeAndAfterEachMethods.class)
  @TestMethodOrder(MethodOrderer.MethodName.class)
  public static class AssumptionInBeforeEachTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder()
        .invokeBeforeAndAfterEachMethods(true)
        .build();

    @BeforeEach
    public void beforeEach() {
      Assumptions.assumeTrue(false, "not on this setup");
    }

    @Test
    public void test1() {
      CALLS.add("test1");
    }

    @Test
    public void test2() {
      CALLS.add("test2");
    }
  }

  @EnabledIf(ENABLED)
  @ExtendWith(SkipBeforeAndAfterEachMethods.class)
  @TestMethodOrder(MethodOrderer.MethodName.class)
  public static class FailingAfterEachTestCase {

    @RegisterExtension
    ProcessEngineExtension extension = ProcessEngineExtension.builder()
        .invokeBeforeAndAfterEachMethods(true)
        .build();

    @AfterEach
    public void failingAfterEach() {
      CALLS.add("failingAfterEach");
      throw new IllegalStateException("afterEach failed");
    }

    @AfterEach
    public void otherAfterEach() {
      CALLS.add("otherAfterEach");
    }

    @Test
    public void test1() {
      CALLS.add("test1");
    }

    @Test
    public void test2() {
      CALLS.add("test2");
    }
  }

  /**
   * Skips the {@code @BeforeEach} and {@code @AfterEach} methods like
   * Arquillian's {@code ArquillianExtension} does for an embedded container
   * that runs the test through the Arquillian "Local" protocol.
   */
  public static class SkipBeforeAndAfterEachMethods implements InvocationInterceptor {

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
        ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) {
      invocation.skip();
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
        ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) {
      invocation.skip();
    }
  }
}
