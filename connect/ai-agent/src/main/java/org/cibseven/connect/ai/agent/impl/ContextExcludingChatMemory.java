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
package org.cibseven.connect.ai.agent.impl;

import java.util.ArrayList;
import java.util.List;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;

/**
 * Keeps the rendered process-context block out of the persisted chat memory
 * while the current invocation still sends it to the model.
 *
 * <p>{@code AiServices} adds the system message to the chat memory and then
 * builds every request of the invocation — the first one and each one after a
 * tool call — from {@link #messages()}. The context block is appended to the
 * system message by a {@code systemMessageTransformer}, so without this wrapper
 * {@code MessageWindowChatMemory} would hand the whole block, values included,
 * to its store. With the default {@code ProcessVariableChatMemoryStore} that is
 * one more process variable and, at history level FULL, one historic detail per
 * turn holding the raw values the chat log deliberately leaves out.
 *
 * <p>The delegate therefore only ever stores the system message without the
 * block. The full message, as this invocation built it, is held here and
 * substituted back into {@link #messages()}. An instance lives for one
 * {@code AiServices} build, i.e. one connector invocation; the next invocation
 * renders the block from the then-current variable values again.
 *
 * <p>A memory written before this wrapper existed still holds an old block in
 * its system message. It is replaced on the first turn, because the stripped
 * message differs from the stored one and {@code MessageWindowChatMemory}
 * replaces a differing system message.
 */
final class ContextExcludingChatMemory implements ChatMemory {

  private final ChatMemory delegate;
  private final String contextBlock;
  private final String separatedContextBlock;

  /** The system message of this invocation, block included; null until added. */
  private SystemMessage currentSystemMessage;

  ContextExcludingChatMemory(ChatMemory delegate, String contextBlock) {
    this.delegate = delegate;
    this.contextBlock = contextBlock;
    this.separatedContextBlock = AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR + contextBlock;
  }

  @Override
  public Object id() {
    return delegate.id();
  }

  @Override
  public void add(ChatMessage message) {
    if (message instanceof SystemMessage) {
      String text = ((SystemMessage) message).text();
      String stripped = stripContextBlock(text);
      if (stripped != null) {
        currentSystemMessage = (SystemMessage) message;
        // A prompt that is the block alone leaves nothing to store. A
        // SystemMessage cannot be blank, so the delegate keeps whatever system
        // message it had; messages() replaces it with the current one anyway.
        if (!stripped.isBlank()) {
          delegate.add(SystemMessage.from(stripped));
        }
        return;
      }
    }
    delegate.add(message);
  }

  @Override
  public List<ChatMessage> messages() {
    List<ChatMessage> messages = new ArrayList<>(delegate.messages());
    if (currentSystemMessage == null) {
      return messages;
    }
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof SystemMessage) {
        messages.set(i, currentSystemMessage);
        return messages;
      }
    }
    messages.add(0, currentSystemMessage);
    return messages;
  }

  @Override
  public void clear() {
    currentSystemMessage = null;
    delegate.clear();
  }

  /**
   * Returns {@code text} without the trailing context block, or {@code null}
   * when it does not carry the block. Mirrors
   * {@code AgentConnectorImpl.appendContextBlock}: either instruction,
   * separator and block, or the block alone.
   */
  private String stripContextBlock(String text) {
    if (text == null) {
      return null;
    }
    if (text.endsWith(separatedContextBlock)) {
      return text.substring(0, text.length() - separatedContextBlock.length());
    }
    if (text.equals(contextBlock)) {
      return "";
    }
    return null;
  }
}
