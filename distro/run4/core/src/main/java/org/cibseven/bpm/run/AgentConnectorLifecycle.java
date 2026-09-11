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
package org.cibseven.bpm.run;

import jakarta.annotation.PreDestroy;
import org.cibseven.connect.ai.agent.impl.ProcessStarterTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Releases the AI Agent connector's {@code ENGINE_EXECUTOR} thread pool
 * when the Spring application context closes. Fires on
 * <ul>
 *   <li>Spring Boot DevTools reload,</li>
 *   <li>application-server stop (Tomcat / Wildfly redeploy),</li>
 *   <li>JVM exit.</li>
 * </ul>
 *
 * <p>Without this hook the pool's cached daemon threads outlive the
 * reloaded JAR and pin the previous {@code ClassLoader}, eventually
 * exhausting Metaspace on hot-redeploy-heavy workflows. See
 * {@code CIB7-1416} / §6a of the {@code CIB7-1299} review.
 *
 * <p>The {@link ConditionalOnClass} guard means the bean is skipped (and
 * the typed reference to {@link ProcessStarterTool} is never resolved)
 * when the AI Agent connector JAR is not on the classpath — keeping the
 * AI Agent strictly optional for the run distro.
 */
@Configuration
@ConditionalOnClass(name = "org.cibseven.connect.ai.agent.impl.ProcessStarterTool")
public class AgentConnectorLifecycle {

  @PreDestroy
  public void shutdown() {
    ProcessStarterTool.shutdownExecutor();
  }
}
