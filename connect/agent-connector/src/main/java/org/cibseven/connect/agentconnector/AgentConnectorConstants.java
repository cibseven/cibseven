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
package org.cibseven.connect.agentconnector;

/**
 * Internal constants shared across the agent connector implementation.
 */
public final class AgentConnectorConstants {

  /** Default OpenAI-compatible model used when no {@code model} input parameter is provided. */
  public static final String DEFAULT_MODEL = "gpt-5.4-nano";

  /** Default base URL for the OpenAI-compatible API endpoint. */
  public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

  /**
   * Environment variable that overrides the base URL at the process level.
   * Set to e.g. {@code https://openrouter.ai/api/v1} to route requests through OpenRouter.
   */
  public static final String ENV_BASE_URL = "OPENAI_BASE_URL";

  // ── RAG / pgvector defaults ────────────────────────────────────────────────

  /** Default PostgreSQL port when {@code pgPort} input parameter is not provided. */
  public static final String DEFAULT_PG_PORT = "5432";

  /** Default table name for the embedding store when {@code pgTable} is not provided. */
  public static final String DEFAULT_PG_TABLE = "langchain4j_embeddings";

  /** Default maximum number of RAG results returned per query. */
  public static final int DEFAULT_MAX_RAG_RESULTS = 5;

  /**
   * Default minimum similarity score (0.0–1.0) for a result to be included.
   * Set to 0.0 (no filtering) so results are always returned; tune per deployment.
   * AllMiniLmL6V2 typically yields cosine similarities in the 0.3–0.7 range for related content.
   */
  public static final double DEFAULT_MIN_RAG_SCORE = 0.0;

  /**
   * Default embedding vector dimension.
   * {@code AllMiniLmL6V2EmbeddingModel} produces 384-dimensional vectors.
   */
  public static final int DEFAULT_EMBEDDING_DIMENSION = 384;

  // ── Knowledge ingestion defaults ──────────────────────────────────────────

  /** Default maximum characters per text chunk before splitting. */
  public static final int DEFAULT_CHUNK_SIZE = 500;

  /** Default character overlap between consecutive chunks. */
  public static final int DEFAULT_CHUNK_OVERLAP = 50;

  // ── Agent connector flag ──────────────────────────────────────────────────

  /**
   * Flag that indicates the execution of the agent connector in the process;
   * only possible value is {@code true}.
   */
  public static final String AGENT_CONNECTOR_FLAG_VARIABLE_NAME = "cibseven-langchain4j-agent-connector";

  // ── Chat memory defaults ──────────────────────────────────────────────────

  /**
   * Default sliding window size (number of messages) for the per-memory-id chat
   * memory used when {@code useChatMemory} is activated and no explicit
   * {@code chatMemoryMaxMessages} is provided.
   */
  public static final int DEFAULT_CHAT_MEMORY_MAX_MESSAGES = 20;

  private AgentConnectorConstants() {
    // utility class
  }

}
