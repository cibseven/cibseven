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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.script.ScriptException;

import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.impl.bpmn.parser.ErrorEventDefinition;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.interceptor.CommandContextListener;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;

/**
 * An error definition on an activity a boundary event of a tool is attached to, which remembers
 * what it was offered (CIB7-2166).
 *
 * <p>The engine hands the code of a BPMN error to the boundary event that catches it only through
 * {@code camunda:errorCodeVariable}, a process variable the modeller has to ask for, and a boundary
 * event without an error reference catches every code. Which one it was is asked of the
 * definitions, though: the engine offers the error to the definitions of each scope in turn, and
 * the first that catches it is the one whose boundary event runs next. Before it does, in the same
 * command, the activity the event is attached to is cancelled, and its end listener ({@link
 * AgenticToolEndRecorder}) takes the code from here: also when the boundary event itself continues
 * asynchronously. The engine does not learn about the agent, and the code is kept only on the
 * tool's performance, as an execution-local variable that goes with it.
 *
 * <p>Two kinds, both set by the parse listener:
 * <ul>
 * <li>in place of the engine's own definition of a boundary error event, with the same handler,
 * code, precedence and variables, so it catches exactly what that one caught and the modeller's
 * {@code errorCodeVariable} and {@code errorMessageVariable} are still written;</li>
 * <li>a watch on an activity that has boundary events, none of them an error event: it catches
 * nothing, and only notes that an error thrown there found no handler. The engine ends such an
 * activity as if it had completed, and its end must not count as the repair of an interruption
 * one of its other events recorded.</li>
 * </ul>
 * An error nothing catches is noted by either kind, as long as nothing caught it after all.
 */
public class AgenticCaughtError extends ErrorEventDefinition {

  private static final long serialVersionUID = 1L;

  /**
   * The last code a definition of this kind caught on this thread, with the command it was caught
   * in. Taken by the end listener of the activity the catching event is attached to, and cleared
   * when that command closes or fails, so no command holds on to another one's context.
   */
  private static final ThreadLocal<Caught> CAUGHT = new ThreadLocal<Caught>();

  /**
   * The BPMN errors definitions of this kind were offered and did not catch, while none caught
   * them, one per activity: an error is offered to the definitions of every scope on its way out,
   * so to those of an activity around the one that threw it too. Nothing at all may catch it: the
   * engine then ends the throwing activity as if it had completed ({@code BpmnExceptionHandler},
   * {@code enableExceptionsAfterUnhandledBpmnError} off by default), and its end must not count as
   * the repair of an earlier interruption.
   */
  private static final ThreadLocal<List<Caught>> UNCAUGHT = new ThreadLocal<List<Caught>>();

  /**
   * Clears what this thread caught or was offered in the command that closes, and only that: a
   * command that runs inside another and closes first leaves the outer one's alone. Stateless, so
   * one serves them all.
   */
  private static final CommandContextListener FORGET = new CommandContextListener() {
    @Override
    public void onCommandContextClose(CommandContext commandContext) {
      forget(commandContext);
    }

    @Override
    public void onCommandFailed(CommandContext commandContext, Throwable t) {
      forget(commandContext);
    }
  };

  private static void forget(CommandContext commandContext) {
    Caught caught = CAUGHT.get();
    if (caught != null && caught.command == commandContext) {
      CAUGHT.remove();
    }
    List<Caught> uncaught = UNCAUGHT.get();
    if (uncaught != null) {
      for (Iterator<Caught> it = uncaught.iterator(); it.hasNext();) {
        if (it.next().command == commandContext) {
          it.remove();
        }
      }
      if (uncaught.isEmpty()) {
        UNCAUGHT.remove();
      }
    }
  }

  /**
   * What a definition caught or was offered: in which command, on which activity, which boundary
   * event handles it, which code, and for a Java exception mapped to an error, the class thrown.
   */
  static final class Caught {
    private final CommandContext command;
    private final String attachedTo;
    private final String handlerActivityId;
    private final String errorCode;
    private final String exceptionClass;

    private Caught(CommandContext command, String attachedTo, String handlerActivityId,
        String errorCode, String exceptionClass) {
      this.command = command;
      this.attachedTo = attachedTo;
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

  /** The id of the activity this definition sits on, the one its boundary events are attached to. */
  private final String attachedTo;

  /** Whether this is a watch, which catches nothing. */
  private final boolean watchOnly;

  /** In place of {@code original}, a definition on {@code attachedTo}: catches what it caught. */
  public AgenticCaughtError(ErrorEventDefinition original, ActivityImpl attachedTo) {
    super(original.getHandlerActivityId());
    setErrorCode(original.getErrorCode());
    // getPrecedence() adds one for a definition with a code; the field holds it without.
    setPrecedence(original.getPrecedence() - (original.getErrorCode() != null ? 1 : 0));
    setErrorCodeVariable(original.getErrorCodeVariable());
    setErrorMessageVariable(original.getErrorMessageVariable());
    this.attachedTo = attachedTo.getId();
    this.watchOnly = false;
  }

  /**
   * A watch on {@code attachedTo}, which catches nothing. Its handler is {@code boundaryEvent}, one
   * of the activity's own boundary events: the engine looks the handler up before it asks, and
   * would take the activity itself, a sub process around the throwing activity, for an event sub
   * process that throws the error again, and never ask.
   */
  private AgenticCaughtError(ActivityImpl attachedTo, ActivityImpl boundaryEvent) {
    super(boundaryEvent.getId());
    this.attachedTo = attachedTo.getId();
    this.watchOnly = true;
  }

  /** A watch on {@code attachedTo}, handled by its boundary event {@code boundaryEvent}. */
  static AgenticCaughtError watch(ActivityImpl attachedTo, ActivityImpl boundaryEvent) {
    return new AgenticCaughtError(attachedTo, boundaryEvent);
  }

  @Override
  public boolean catchesError(String errorCode) {
    boolean catches = !watchOnly && super.catchesError(errorCode);
    CommandContext command = Context.getCommandContext();
    if (command == null) {
      return catches;
    }
    if (catches) {
      remember(command, errorCode, null);
    } else {
      List<Caught> uncaught = UNCAUGHT.get();
      if (uncaught == null) {
        uncaught = new ArrayList<Caught>();
        UNCAUGHT.set(uncaught);
      }
      uncaught.add(new Caught(command, attachedTo, getHandlerActivityId(), errorCode, null));
      command.registerCommandContextListener(FORGET);
    }
    return catches;
  }

  /**
   * A Java exception mapped to an error: its code is the class this definition names, which may
   * be a superclass of the one thrown. That one is kept too, unwrapped as the engine unwraps it
   * to match. An exception nothing catches fails the command, so nothing is noted for it.
   */
  @Override
  public boolean catchesException(Exception ex) {
    boolean catches = !watchOnly && super.catchesException(ex);
    CommandContext command = Context.getCommandContext();
    if (catches && command != null) {
      Exception thrown = ex;
      while ((thrown instanceof ProcessEngineException || thrown instanceof ScriptException)
          && thrown.getCause() instanceof Exception) {
        thrown = (Exception) thrown.getCause();
      }
      remember(command, getErrorCode(), thrown.getClass().getName());
    }
    return catches;
  }

  private void remember(CommandContext command, String errorCode, String exceptionClass) {
    CAUGHT.set(new Caught(command, attachedTo, getHandlerActivityId(), errorCode, exceptionClass));
    // Caught after all, by this definition: what the others were offered on its way is moot.
    List<Caught> uncaught = UNCAUGHT.get();
    if (uncaught != null) {
      for (Iterator<Caught> it = uncaught.iterator(); it.hasNext();) {
        if (it.next().command == command) {
          it.remove();
        }
      }
    }
    command.registerCommandContextListener(FORGET);
  }

  /**
   * What a boundary event attached to {@code attachedTo} caught in the running command, or null
   * when none did. Taken, so it is reported once; what belongs to another activity stays.
   */
  static Caught takeCaughtOn(ActivityImpl attachedTo) {
    Caught caught = CAUGHT.get();
    if (!on(caught, attachedTo)) {
      return null;
    }
    CAUGHT.remove();
    return caught;
  }

  /**
   * Whether a BPMN error thrown by {@code attachedTo}, or inside it, in the running command was
   * offered to the definitions on it and caught by none, nor by anything else. Taken.
   */
  static boolean takeUncaughtOn(ActivityImpl attachedTo) {
    List<Caught> uncaught = UNCAUGHT.get();
    boolean found = false;
    if (uncaught != null) {
      for (Iterator<Caught> it = uncaught.iterator(); it.hasNext();) {
        if (on(it.next(), attachedTo)) {
          it.remove();
          found = true;
        }
      }
    }
    return found;
  }

  /** Whether {@code caught} is of the running command and of a definition on {@code attachedTo}. */
  private static boolean on(Caught caught, ActivityImpl attachedTo) {
    return caught != null && caught.command == Context.getCommandContext()
        && caught.attachedTo.equals(attachedTo.getId());
  }
}
