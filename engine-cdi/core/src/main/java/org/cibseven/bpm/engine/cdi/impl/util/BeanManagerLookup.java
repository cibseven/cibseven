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
package org.cibseven.bpm.engine.cdi.impl.util;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletContext;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.cibseven.bpm.application.ProcessApplicationInterface;
import org.cibseven.bpm.application.ProcessApplicationReference;
import org.cibseven.bpm.application.ProcessApplicationUnavailableException;
import org.cibseven.bpm.application.impl.ServletProcessApplication;
import org.cibseven.bpm.engine.impl.context.Context;

import jakarta.enterprise.inject.spi.CDI;

import org.cibseven.bpm.engine.ProcessEngineException;

public class BeanManagerLookup {

  /**
   * The ServletContext attribute name Weld itself uses to publish the BeanManager
   * of the webapp it just bootstrapped (see org.jboss.weld.environment.servlet.WeldServletLifecycle).
   */
  private static final String WELD_SERVLET_CONTEXT_BEAN_MANAGER_ATTRIBUTE =
      "org.jboss.weld.environment.servlet." + BeanManager.class.getName();

     /** holds a local beanManager if no jndi is available */
  public static BeanManager localInstance;

  /** provide a custom jndi lookup name */
  public static String jndiName;

  public static BeanManager getBeanManager() {
    
    BeanManager beanManager = lookupBeanManagerViaProcessApplication();
    
    if(beanManager != null)
      return beanManager;
      
    beanManager = lookupBeanManagerInJndi();
    if (beanManager != null) {
      return beanManager;
    }

    if (localInstance != null) {
      return localInstance;
    }

    throw new ProcessEngineException(
        "Could not lookup beanmanager in jndi. If no jndi is available, set the beanmanger to the 'localInstance' property of this class.");
  }

  /**
   * Uses the engine's own "current process application" context (set by
   * Context.executeWithinProcessApplication around EL evaluation, event listeners, etc.)
   * to find the exact ServletProcessApplication/WAR that is currently executing, and reads
   * the BeanManager that Weld published on that WAR's own ServletContext. This works across
   * the classloader boundary between shared engine-cdi code and a WAR-local CDI container,
   * unlike CDI.current() (which can silently pick an arbitrary, possibly wrong, deployed WAR)
   * or the classic JNDI Resource/factory approach (which doesn't work with this Weld version
   * because it registers containers under a dynamic id, not a fixed JNDI-resolvable one).
   */
  private static BeanManager lookupBeanManagerViaProcessApplication() {
    ProcessApplicationReference reference = Context.getCurrentProcessApplication();
    if (reference == null) {
      return null;
    }
    try {
      ProcessApplicationInterface processApplication = reference.getProcessApplication().getRawObject();
      if (processApplication instanceof ServletProcessApplication) {
        ServletContext servletContext = ((ServletProcessApplication) processApplication).getServletContext();
        if (servletContext != null) {
          Object beanManager = servletContext.getAttribute(WELD_SERVLET_CONTEXT_BEAN_MANAGER_ATTRIBUTE);
          if (beanManager instanceof BeanManager) {
            return (BeanManager) beanManager;
          }
        }
      }
    } catch (ProcessApplicationUnavailableException e) {
      // process application is not (or no longer) available; fall through to other lookups
    }
    return null;
}

  private static BeanManager lookupBeanManagerInJndi() {

    if (jndiName != null) {
      try {
        return (BeanManager) InitialContext.doLookup(jndiName);
      } catch (NamingException e) {
        throw new ProcessEngineException("Could not lookup beanmanager in jndi using name: '" + jndiName + "'.", e);
      }
    }

    try {
      // in an application server
      return (BeanManager) InitialContext.doLookup("java:comp/BeanManager");
    } catch (NamingException e) {
      // silently ignore
    }
    
    try {
      // in a servlet container
      return (BeanManager) InitialContext.doLookup("java:comp/env/BeanManager");
    } catch (NamingException e) {
      // silently ignore
    }
    
    return null;
   
  }
  
  private static BeanManager lookupBeanManagerViaCdiCurrent() {
    try {
      // Portable CDI 1.1+ API. Resolves against the calling thread's context classloader,
      // which stays correct per-webapp even though this class itself is loaded once from
      // Tomcat's shared classpath. Needed because weld-servlet-shaded 6.x registers each
      // webapp's container under a per-deployment id, so the classic
      // context.xml <Resource factory="org.jboss.weld.resources.ManagerObjectFactory"/>
      // binding (which only ever looks up the fixed id "STATIC_INSTANCE") can never resolve
      // it — confirmed by decompiling that factory and WeldServletLifecycle.
      return CDI.current().getBeanManager();
    } catch (Exception e) {
      // no CDI container active on this thread - fall through like the JNDI attempts above
      return null;
    }
  }

}