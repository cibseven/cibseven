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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.cibseven.connect.ai.agent.AgentConnectorConstants;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

class ContextExcludingChatMemoryTest {

  private static final String BLOCK = AgentConnectorConstants.CONTEXT_BLOCK_OPEN
      + "\nnote (string) = \"secret\"\n" + AgentConnectorConstants.CONTEXT_BLOCK_CLOSE;
  private static final String FULL =
      "be brief" + AgentConnectorConstants.INSTRUCTION_MODE_SEPARATOR + BLOCK;

  private final InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();

  private ContextExcludingChatMemory memory() {
    return new ContextExcludingChatMemory(MessageWindowChatMemory.builder()
        .id("m").maxMessages(20).chatMemoryStore(store).build(), BLOCK);
  }

  @Test
  void storesTheInstructionButReturnsTheFullSystemMessageOnEveryTurnOfTheInvocation() {
    ContextExcludingChatMemory memory = memory();
    memory.add(SystemMessage.from(FULL));
    memory.add(UserMessage.from("hi"));
    memory.add(AiMessage.from("calling a tool"));

    // Tool loop: AiServices rebuilds the request from messages() after each call.
    List<ChatMessage> sent = memory.messages();
    assertThat(sent.get(0)).isEqualTo(SystemMessage.from(FULL));
    assertThat(sent).hasSize(3);

    List<ChatMessage> stored = store.getMessages("m");
    assertThat(stored.get(0)).isEqualTo(SystemMessage.from("be brief"));
    assertThat(stored.toString()).doesNotContain("secret");
  }

  @Test
  void replacesABlockLeftInTheStoreByAnEarlierVersion() {
    store.updateMessages("m", List.of(SystemMessage.from(FULL), UserMessage.from("old")));

    memory().add(SystemMessage.from(FULL));

    // Order is MessageWindowChatMemory's business (a replaced system message
    // moves to the end unless alwaysKeepSystemMessageFirst is set); what matters
    // here is that the old block is gone.
    assertThat(store.getMessages("m"))
        .containsExactlyInAnyOrder(SystemMessage.from("be brief"), UserMessage.from("old"));
  }

  @Test
  void storesNoSystemMessageWhenThePromptIsTheBlockAlone() {
    ContextExcludingChatMemory memory = memory();
    memory.add(SystemMessage.from(BLOCK));
    memory.add(UserMessage.from("hi"));

    assertThat(memory.messages())
        .containsExactly(SystemMessage.from(BLOCK), UserMessage.from("hi"));
    assertThat(store.getMessages("m")).containsExactly(UserMessage.from("hi"));
  }

  @Test
  void passesASystemMessageWithoutTheBlockThrough() {
    ContextExcludingChatMemory memory = memory();
    memory.add(SystemMessage.from("other prompt"));

    assertThat(memory.messages()).containsExactly(SystemMessage.from("other prompt"));
    assertThat(store.getMessages("m")).containsExactly(SystemMessage.from("other prompt"));
  }
}
