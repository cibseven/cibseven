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
package org.cibseven.bpm.application.impl;

import jakarta.servlet.ServletContextEvent;
import org.cibseven.bpm.engine.impl.util.ClassLoaderUtil;

/**
 * Former base class for servlet process applications.
 *
 * @deprecated Both classes are based on {@code jakarta.servlet}; extend
 *             {@link JakartaServletProcessApplication} instead. This class only remains
 *             so that existing subclasses, and {@code web.xml} listener entries that name
 *             this class, keep working.
 */
@Deprecated
public class ServletProcessApplication extends JakartaServletProcessApplication {

  @Override
  protected ClassLoader initProcessApplicationClassloader(ServletContextEvent sce) {
    // this class itself registered as a listener (e.g. in web.xml) instead of a subclass
    if (getClass().equals(ServletProcessApplication.class)) {
      return ClassLoaderUtil.getServletContextClassloader(sce);
    }
    return super.initProcessApplicationClassloader(sce);
  }
}