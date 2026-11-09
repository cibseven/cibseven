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
package org.cibseven.connect.ai.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.AiServices;

/**
 * The AI service an agentic step is sent through ({@code LangChainAgenticAgent}, CIB7-2179), with
 * retrieval: the message reaches the model once, as one text, with what was retrieved and with
 * "{{…}}" as it is. Retrieval reads the message as one text, and AI services add a content argument
 * to the message again unless it is given as a list.
 */
public class AgenticStepAsTextTest {

  @Test
  public void aStepWithRetrievalReachesTheModelOnceAsOneTextWithItsBraces() {
    List<ChatRequest> requests = new ArrayList<>();
    ChatModel model = new ChatModel() {
      @Override
      public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
      }
    };
    AgentConnectorImpl.LangChainAgenticAgent agent =
        AiServices.builder(AgentConnectorImpl.LangChainAgenticAgent.class)
            .chatModel(model)
            .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
            .contentRetriever(query -> Collections.singletonList(Content.from("Kontext aus der Wissensbasis")))
            .build();
    String step = "[STATE] {\"kommentar\":\"Bitte {{name}} pruefen, {{current_date}}\"} [END] Bearbeite den Auftrag.";

    agent.chat("adhoc-1", Collections.singletonList(TextContent.from(step)));

    assertThat(requests).hasSize(1);
    UserMessage user = null;
    for (ChatMessage message : requests.get(0).messages()) {
      if (message instanceof UserMessage) {
        user = (UserMessage) message;
      }
    }
    assertThat(user).isNotNull();
    assertThat(user.hasSingleText()).as("one text, not parts").isTrue();
    String text = user.singleText();
    assertThat(text).contains("Bitte {{name}} pruefen, {{current_date}}")
        .contains("Kontext aus der Wissensbasis");
    assertThat(text.split("Bearbeite den Auftrag\\.", -1)).as("the step once").hasSize(2);
  }
}
