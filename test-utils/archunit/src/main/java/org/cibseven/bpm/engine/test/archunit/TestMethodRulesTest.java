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
package org.cibseven.bpm.engine.test.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards against JUnit 3 style test methods: JUnit 5 only runs methods annotated with
 * {@code @Test} (or another test annotation), a {@code public void testXxx()} method without
 * such an annotation is silently skipped, and the build stays green.
 *
 * <p>Checks the test classes of every module that scans this artifact
 * ({@code <dependenciesToScan>} of the surefire plugin).</p>
 */
@AnalyzeClasses(
    importOptions = {
        ImportOption.DoNotIncludeJars.class,
        ImportOption.OnlyIncludeTests.class
    },
    packages = "org.cibseven"
)
public class TestMethodRulesTest {

  @ArchTest
  public static final ArchRule testMethodsMustBeAnnotatedAsTest = methods()
      .that().haveNameStartingWith("test")
      .and().arePublic()
      .and().areNotStatic()
      .and().haveRawReturnType(void.class)
      .and().haveRawParameterTypes(new Class<?>[0])
      .and().areDeclaredInClassesThat().areTopLevelClasses()
      .should().beAnnotatedWith("org.junit.jupiter.api.Test")
      .orShould().beAnnotatedWith("org.junit.jupiter.params.ParameterizedTest")
      .orShould().beAnnotatedWith("org.junit.jupiter.api.RepeatedTest")
      .orShould().beAnnotatedWith("org.junit.jupiter.api.TestFactory")
      .orShould().beAnnotatedWith("org.junit.jupiter.api.TestTemplate")
      .because("JUnit 5 silently skips JUnit 3 style test methods without @Test")
      .allowEmptyShould(true);

}
