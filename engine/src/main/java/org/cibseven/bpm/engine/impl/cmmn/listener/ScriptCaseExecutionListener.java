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
package org.cibseven.bpm.engine.impl.cmmn.listener;

import org.cibseven.bpm.engine.delegate.CaseExecutionListener;
import org.cibseven.bpm.engine.delegate.DelegateCaseExecution;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.delegate.ScriptInvocation;
import org.cibseven.bpm.engine.impl.scripting.ExecutableScript;

/**
 * An {@link CaseExecutionListener} which invokes a {@link ExecutableScript} when notified.
 *
 * @author Roman Smirnov
 * @author Sebastian Menski
 */
public class ScriptCaseExecutionListener implements CaseExecutionListener {

  protected final ExecutableScript script;

  public ScriptCaseExecutionListener(ExecutableScript script) {
    this.script = script;
  }

  public void notify(DelegateCaseExecution caseExecution) throws Exception {
    ScriptInvocation invocation = new ScriptInvocation(script, caseExecution);
    Context
      .getProcessEngineConfiguration()
      .getDelegateInterceptor()
      .handleInvocation(invocation);
  }

  public ExecutableScript getScript() {
    return script;
  }
}
