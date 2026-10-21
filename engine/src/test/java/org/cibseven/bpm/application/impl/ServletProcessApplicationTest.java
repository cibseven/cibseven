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
package org.cibseven.bpm.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
public class ServletProcessApplicationTest {

  protected ClassLoader servletContextClassLoader;
  protected ServletContextEvent servletContextEvent;

  @BeforeEach
  public void setUp() {
    servletContextClassLoader = new ClassLoader() { };
    ServletContext servletContext = mock(ServletContext.class);
    when(servletContext.getClassLoader()).thenReturn(servletContextClassLoader);
    servletContextEvent = mock(ServletContextEvent.class);
    when(servletContextEvent.getServletContext()).thenReturn(servletContext);
  }

  @Test
  public void shouldBeDetectedAsJakartaServletProcessApplication() {
    // container integrations (WildFly subsystem, CDI, Spring) only check for the Jakarta class
    assertThat(new CustomServletProcessApplication()).isInstanceOf(JakartaServletProcessApplication.class);
  }

  @Test
  public void shouldUseServletContextClassLoaderWhenRegisteredDirectly() {
    ServletProcessApplication processApplication = new ServletProcessApplication();

    assertThat(processApplication.initProcessApplicationClassloader(servletContextEvent)).isSameAs(servletContextClassLoader);
  }

  @Test
  public void shouldUseClassLoaderOfSubclass() {
    ServletProcessApplication processApplication = new CustomServletProcessApplication();

    assertThat(processApplication.initProcessApplicationClassloader(servletContextEvent))
        .isSameAs(CustomServletProcessApplication.class.getClassLoader())
        .isNotSameAs(servletContextClassLoader);
  }

  public static class CustomServletProcessApplication extends ServletProcessApplication {
  }

}
