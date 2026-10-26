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

import javax.script.ScriptException;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.parser.ErrorEventDefinition;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandContextListener;
import org.cibseven.bpm.engine.impl.pvm.PvmActivity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;

/**
 * The error definition of a boundary error event on a tool, which remembers the code it caught
 * (CIB7-2166).
 *
 * <p>The engine hands the code of a BPMN error to the boundary event that catches it only through
 * {@code camunda:errorCodeVariable}, a process variable the modeller has to ask for, and a boundary
 * event without an error reference catches every code. Which one it was is asked of this
 * definition, though: the engine offers the error to the definitions of each scope in turn, and
 * the first that catches it is the one whose boundary event runs next. Before it does, in the same
 * command, the activity the event is attached to is cancelled, and its end listener ({@link
 * AgenticToolEndRecorder}) takes the code from here: also when the boundary event itself continues
 * asynchronously. The engine does not learn about the agent, and the code is kept only on the
 * tool's performance, as an execution-local variable that goes with it.
 *
 * <p>Put in place of the engine's own definition by the parse listener, with the same handler,
 * code, precedence and variables, so it catches exactly what that one caught and the modeller's
 * {@code errorCodeVariable} and {@code errorMessageVariable} are still written.
 */
public class AgenticCaughtError extends ErrorEventDefinition {

  private static final long serialVersionUID = 1L;

  /**
   * The last code a definition of this kind caught on this thread, with the command it was caught
   * in. Taken by the end listener of the activity the catching event is attached to, and cleared
   * when the command closes or fails, so no command holds on to another one's context.
   */
  private static final ThreadLocal<Caught> CAUGHT = new ThreadLocal<Caught>();

  /** Clears what this thread caught, as its command closes. Stateless, so one serves them all. */
  private static final CommandContextListener FORGET = new CommandContextListener() {
    @Override
    public void onCommandContextClose(CommandContext commandContext) {
      CAUGHT.remove();
    }

    @Override
    public void onCommandFailed(CommandContext commandContext, Throwable t) {
      CAUGHT.remove();
    }
  };

  /**
   * What a definition caught: in which command, which boundary event handles it, which code, and
   * for a Java exception mapped to an error, the class actually thrown.
   */
  static final class Caught {
    private final CommandContext command;
    private final String handlerActivityId;
    private final String errorCode;
    private final String exceptionClass;

    private Caught(CommandContext command, String handlerActivityId, String errorCode,
        String exceptionClass) {
      this.command = command;
      this.handlerActivityId = handlerActivityId;
      this.errorCode = errorCode;
      this.exceptionClass = exceptionClass;
    }

    String handlerActivityId() {
      return handlerActivityId;
    }

    String errorCode() {
      return errorCode;
    }

    /** The class of the Java exception thrown, or null for a BPMN error. */
    String exceptionClass() {
      return exceptionClass;
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
      remember(errorCode, null);
    }
    return catches;
  }

  /**
   * A Java exception mapped to an error: its code is the class this definition names, which may
   * be a superclass of the one thrown. That one is kept too, unwrapped as the engine unwraps it
   * to match.
   */
  @Override
  public boolean catchesException(Exception ex) {
    boolean catches = super.catchesException(ex);
    if (catches) {
      Exception thrown = ex;
      while ((thrown instanceof ProcessEngineException || thrown instanceof ScriptException)
          && thrown.getCause() instanceof Exception) {
        thrown = (Exception) thrown.getCause();
      }
      remember(getErrorCode(), thrown.getClass().getName());
    }
    return catches;
  }

  private void remember(String errorCode, String exceptionClass) {
    CommandContext command = Context.getCommandContext();
    if (command != null) {
      CAUGHT.set(new Caught(command, getHandlerActivityId(), errorCode, exceptionClass));
      command.registerCommandContextListener(FORGET);
    }
  }

  /**
   * What a boundary event attached to {@code attachedTo} caught in the running command, or null
   * when none did. Taken, so it is reported once; what belongs to another activity stays.
   */
  static Caught takeCaughtOn(ActivityImpl attachedTo) {
    Caught caught = CAUGHT.get();
    if (caught == null || caught.command != Context.getCommandContext()) {
      return null;
    }
    PvmActivity handler = attachedTo.getProcessDefinition().findActivity(caught.handlerActivityId);
    if (handler == null || handler.getEventScope() != attachedTo) {
      return null;
    }
    CAUGHT.remove();
    return caught;
  }
}
