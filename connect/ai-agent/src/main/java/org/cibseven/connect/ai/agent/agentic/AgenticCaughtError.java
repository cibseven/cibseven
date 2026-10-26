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
package org.cibseven.connect.ai.agent.agentic;

import org.cibseven.bpm.engine.impl.bpmn.parser.ErrorEventDefinition;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;

/**
 * The error definition of a boundary error event on a tool, which remembers the code it caught
 * (CIB7-2166).
 *
 * <p>The engine hands the code of a BPMN error to the boundary event that catches it only through
 * {@code camunda:errorCodeVariable}, a variable written into the process, and a boundary event
 * without an error reference catches every code. Which one it was is asked of this definition,
 * though: the engine offers the error to the definitions of each scope in turn, and the first that
 * catches it is the one whose boundary event runs next, in the same command. So this definition
 * keeps the code it said yes to, and the boundary event's start listener ({@link
 * AgenticToolEndRecorder}) takes it, before anything else runs. Nothing reaches the process's
 * variables, and the engine does not learn about the agent.
 *
 * <p>Put in place of the engine's own definition by the parse listener, with the same handler,
 * code, precedence and variables, so it catches exactly what that one caught.
 */
public class AgenticCaughtError extends ErrorEventDefinition {

  private static final long serialVersionUID = 1L;

  /**
   * The last code a definition of this kind caught on this thread, with the command it was caught
   * in. Taken and cleared by the boundary event's start listener; the command and the handler are
   * compared, so a code left behind by a command that failed in between is never taken for another.
   */
  private static final ThreadLocal<Caught> CAUGHT = new ThreadLocal<Caught>();

  private static final class Caught {
    private final CommandContext command;
    private final String handlerActivityId;
    private final String errorCode;

    private Caught(CommandContext command, String handlerActivityId, String errorCode) {
      this.command = command;
      this.handlerActivityId = handlerActivityId;
      this.errorCode = errorCode;
    }
  }

  public AgenticCaughtError(ErrorEventDefinition original) {
    super(original.getHandlerActivityId());
    setErrorCode(original.getErrorCode());
    // getPrecedence() adds one for a definition with a code; the field holds it without.
    setPrecedence(original.getPrecedence() - (original.getErrorCode() != null ? 1 : 0));
    setErrorCodeVariable(original.getErrorCodeVariable());
    setErrorMessageVariable(original.getErrorMessageVariable());
  }

  @Override
  public boolean catchesError(String errorCode) {
    boolean catches = super.catchesError(errorCode);
    if (catches) {
      remember(errorCode);
    }
    return catches;
  }

  /** A Java exception mapped to an error: its code is the class name this definition names. */
  @Override
  public boolean catchesException(Exception ex) {
    boolean catches = super.catchesException(ex);
    if (catches) {
      remember(getErrorCode());
    }
    return catches;
  }

  private void remember(String errorCode) {
    CommandContext command = Context.getCommandContext();
    if (command != null) {
      CAUGHT.set(new Caught(command, getHandlerActivityId(), errorCode));
    }
  }

  /**
   * The code the boundary event {@code handlerActivityId} caught in the running command, or null
   * when it caught none in it. Cleared either way.
   */
  static String takeCaughtCode(String handlerActivityId) {
    Caught caught = CAUGHT.get();
    CAUGHT.remove();
    if (caught == null || caught.command != Context.getCommandContext()
        || !caught.handlerActivityId.equals(handlerActivityId)) {
      return null;
    }
    return caught.errorCode;
  }
}
