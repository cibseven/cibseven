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
package org.cibseven.bpm.quarkus.engine.extension.impl;

import java.util.List;

import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.jobexecutor.ExecuteJobsRunnable;
import org.cibseven.bpm.engine.impl.util.ClassLoaderUtil;

/**
 * CIB7-1959 Quarkus job executor creates threads without the Quarkus TCCL (SRCFG00015)
 *
 * <p>An {@link ExecuteJobsRunnable} that runs jobs with the Quarkus class loader as thread
 * context class loader, instead of the process engine's class loader.
 *
 * <p>{@link ExecuteJobsRunnable#switchClassLoader()} switches the TCCL to
 * {@code ProcessEngine.class.getClassLoader()} so engine classes can be loaded during job
 * execution (CAM-10379). Under Quarkus that switch is unnecessary - the Quarkus class loader
 * already sees the engine classes - and it breaks configuration lookups: SmallRye Config
 * resolves {@code @ConfigProperty} injection points by an exact identity match of the current
 * TCCL against its registered class loaders, with no parent-class-loader fallback. When the
 * engine class loader is not the very instance the Quarkus configuration was registered with,
 * a CDI bean carrying {@code @ConfigProperty} that is lazily created from a job fails with
 * {@code SRCFG00015: No configuration is available for this class loader}, which the engine
 * reports as a failed job incident.
 *
 * <p>This mirrors {@code JcaInflowExecuteJobsRunnable}, which skips the switch for the same
 * structural reason.
 *
 * @see ExecuteJobsRunnable#switchClassLoader()
 */
public class QuarkusExecuteJobsRunnable extends ExecuteJobsRunnable {

  protected final ClassLoader contextClassLoader;

  public QuarkusExecuteJobsRunnable(List<String> jobIds,
                                    ProcessEngineImpl processEngine,
                                    ClassLoader contextClassLoader) {
    super(jobIds, processEngine);
    this.contextClassLoader = contextClassLoader;
  }

  /**
   * CIB7-1959: switches to the Quarkus class loader rather than the process engine's, so that
   * configuration remains resolvable while the job runs. The caller restores the returned
   * class loader once job execution has finished.
   */
  @Override
  protected ClassLoader switchClassLoader() {
    ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
    if (contextClassLoader != null) {
      ClassLoaderUtil.setContextClassloader(contextClassLoader);
    }
    return previousClassLoader;
  }

}
