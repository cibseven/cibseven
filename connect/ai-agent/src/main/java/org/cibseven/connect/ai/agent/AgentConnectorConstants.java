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
package org.cibseven.connect.ai.agent;

/**
 * Internal constants shared across the agent connector implementation.
 */
public final class AgentConnectorConstants {

  /**
   * Hard-coded fallback model used when no {@code model} input parameter is set on
   * the BPMN activity <em>and</em> the platform operator has not configured an
   * override via {@link #DEFAULT_MODEL_PROPERTY} or {@link #DEFAULT_MODEL_ENV_VAR}.
   *
   * <p>Operators set their organisation-wide default through one of:
   * <ul>
   *   <li>JVM system property {@value #DEFAULT_MODEL_PROPERTY}
   *       (e.g. {@code -Dcibseven.connect.ai-agent.defaultModel=<provider-model>}),</li>
   *   <li>environment variable {@value #DEFAULT_MODEL_ENV_VAR},</li>
   *   <li>or, in the run distro, the commented YAML stanza under
   *       {@code cibseven.connect.ai-agent} in {@code default.yml}.</li>
   * </ul>
   *
   * <p>Resolution order is implemented by {@link #resolveDefaultModel()}.
   */
  public static final String DEFAULT_MODEL = "gpt-5.4-nano";

  /**
   * JVM system property that overrides {@link #DEFAULT_MODEL} for the deployment.
   * Wins over {@link #DEFAULT_MODEL_ENV_VAR} when both are set.
   */
  public static final String DEFAULT_MODEL_PROPERTY = "cibseven.connect.ai-agent.defaultModel";

  /** Environment-variable fallback for {@link #DEFAULT_MODEL_PROPERTY}. */
  public static final String DEFAULT_MODEL_ENV_VAR = "CIBSEVEN_CONNECT_AI_AGENT_DEFAULT_MODEL";

  /**
   * Environment-variable lookup seam. Defaults to {@link System#getenv(String)};
   * tests replace it to simulate env vars without spawning a new JVM. Always
   * restore to {@code System::getenv} after the test.
   */
  static java.util.function.Function<String, String> ENV_READER = System::getenv;

  /**
   * Resolves the deployment-wide default model from
   * {@link #DEFAULT_MODEL_PROPERTY} → {@link #DEFAULT_MODEL_ENV_VAR} →
   * {@link #DEFAULT_MODEL}. Empty / blank values are treated as unset so an
   * operator can re-enable the hard-coded fallback by clearing the override.
   */
  public static String resolveDefaultModel() {
    String fromSys = System.getProperty(DEFAULT_MODEL_PROPERTY);
    if (fromSys != null && !fromSys.trim().isEmpty()) {
      return fromSys.trim();
    }
    String fromEnv = ENV_READER.apply(DEFAULT_MODEL_ENV_VAR);
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return fromEnv.trim();
    }
    return DEFAULT_MODEL;
  }

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
  public static final String AGENT_CONNECTOR_FLAG_VARIABLE_NAME = "cibseven-connect-ai-agent";

  /**
   * Prefix for the per-activity chat log process variables, so each service
   * task running the connector writes its own chat log without further
   * configuration from the BPMN designer. The chat log of an activity is stored
   * under {@code AGENT_CONNECTOR_LOG_PREFIX + <activityId> +
   * AGENT_CONNECTOR_LOG_CHUNK_SEPARATOR + <eventSeq>}, see
   * {@link #AGENT_CONNECTOR_LOG_CHUNK_SEPARATOR}.
   */
  public static final String AGENT_CONNECTOR_LOG_PREFIX = "cibseven-connect-ai-agent_";

  /**
   * Separates the activity id from the chunk number in a chat-log variable:
   * {@code AGENT_CONNECTOR_LOG_PREFIX + <activityId> + "~" + <eventSeq>}, with
   * the {@code eventSeq} padded with zeros to ten digits, one variable per model call holding its events, written once (CIB7-2156). A
   * BPMN id cannot contain {@code ~}, so the activity id is everything before
   * it; and {@code ~} is unreserved in a URL, so the name goes into a REST path
   * such as {@code /process-instance/{id}/variables/{varName}} as it is.
   *
   * <p>How a reader puts an activity's chat log together, from all variables
   * whose name starts with {@link #AGENT_CONNECTOR_LOG_PREFIX}:
   * <ol>
   *   <li>the activity id is the part of the name after the prefix and before
   *       the last {@code ~}; a name without {@code ~} is the activity id
   *       itself;</li>
   *   <li>the variable without a suffix comes first: a log written before
   *       chunking, by a version that rewrote the whole log on every event;</li>
   *   <li>then the chunks, in the order of their suffix. The suffix is the
   *       {@code eventSeq} padded with zeros to ten digits, such as
   *       {@code ~0000000004} before {@code ~0000000010}, so the numeric order
   *       and the order as text (REST, database, webclient) are the same. Each
   *       holds a JSON array of events; concatenated they are the timeline.</li>
   * </ol>
   *
   * <p>Concurrency: the numbering is one timeline only for invocations of an
   * activity that follow each other. Two at the same time, such as parallel
   * multi-instance or two branches reaching the same activity, each continue
   * from what is committed and so name their chunk alike, as they wrote the one
   * variable before. The second insert then fails on the engine's unique index
   * {@code ACT_UNIQ_VARIABLE}, as the second update failed before, and the
   * transaction of the second fails; run in a job, the job is retried. The
   * engine reports that violation as an {@code OptimisticLockingException}
   * on H2, MySQL, MariaDB, PostgreSQL, SQL Server and Oracle, and as a plain
   * persistence error on DB2.
   *
   * <p>Compatibility: a reader that does not know the separator, such as a
   * webclient from before this version, takes every chunk for a log of its own
   * activity and shows one timeline per model call. Readers have to be updated
   * together with the connector; a process instance running across the upgrade
   * keeps its old variable and continues in chunks after it.
   */
  public static final String AGENT_CONNECTOR_LOG_CHUNK_SEPARATOR = "~";

  // ── Chat memory defaults ──────────────────────────────────────────────────

  /**
   * Prefix for the per-{@code memoryId} chat-memory process variable. The full
   * name is {@code AGENT_CONNECTOR_MEMORY_PREFIX + <memoryId>}.
   *
   * <p>Deliberately uses a {@code -memory_} infix rather than extending
   * {@link #AGENT_CONNECTOR_LOG_PREFIX}: the webclient enumerates chat-log
   * variables with {@code name.startsWith("cibseven-connect-ai-agent_")}, so a
   * name under that prefix would surface memory blobs in the AI chat UI and be
   * parsed as audit events.
   */
  public static final String AGENT_CONNECTOR_MEMORY_PREFIX =
      "cibseven-connect-ai-agent-memory_";

  /**
   * Default sliding window size (number of messages) for the per-memory-id chat
   * memory used when {@code useChatMemory} is activated and no explicit
   * {@code chatMemoryMaxMessages} is provided.
   */
  public static final int DEFAULT_CHAT_MEMORY_MAX_MESSAGES = 20;

  // ── Process context (declared process-variable block) ─────────────────────

  /**
   * Opening delimiter of the process-context block appended to the system
   * prompt when {@code contextVariables} is configured.
   *
   * <p>The block is delimited rather than concatenated so the model can tell
   * process data apart from the deployer's instruction. Any occurrence of this
   * token (or {@link #CONTEXT_BLOCK_CLOSE}) inside a variable value is neutered
   * by the renderer — otherwise a variable holding user- or document-sourced
   * text could close the block early and have the remainder read as
   * instructions.
   */
  public static final String CONTEXT_BLOCK_OPEN = "<process-context>";

  /** Closing delimiter of the process-context block. See {@link #CONTEXT_BLOCK_OPEN}. */
  public static final String CONTEXT_BLOCK_CLOSE = "</process-context>";

  /**
   * Maximum number of characters per context variable value, counted on the
   * value itself, before escaping. At least what a string variable holds in the
   * database (4000 characters, 2000 on Oracle), so a string variable always fits.
   * A required value that is longer fails the activity, naming the variable and
   * its length; an optional one is sent cut, which is visible both in the block
   * and in the {@code context} audit event. JSON and XML object variables have no
   * database limit, so for them this is the real cap.
   */
  public static final int DEFAULT_MAX_CONTEXT_VALUE_CHARS = 4000;

  /**
   * Maximum number of characters of the whole rendered process-context block.
   * Variables that no longer fit are omitted from the tail, and the omission is
   * stated in the block and recorded in the {@code context} audit event.
   */
  public static final int DEFAULT_MAX_CONTEXT_BLOCK_CHARS = 20000;

  // ── Documents (files from variables as native LLM attachments) ────────────

  /**
   * Maximum size of a single document, in raw bytes before Base64 encoding.
   * Checked before encoding because Base64 inflates by roughly a third and the
   * encoded copy is what ends up in memory and on the wire.
   *
   * <p>A document that large in a prompt is nearly always a modelling mistake,
   * and the failure should come from us rather than from a provider rejecting
   * the request minutes later.
   */
  public static final int DEFAULT_MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

  /** Maximum combined raw size of all documents attached to one invocation. */
  public static final int DEFAULT_MAX_TOTAL_DOCUMENT_BYTES = 20 * 1024 * 1024;

  /** Maximum number of documents attached to one invocation. */
  public static final int DEFAULT_MAX_DOCUMENT_COUNT = 10;

  /**
   * Opening delimiter wrapped around a text document handed to the model.
   * Text files are the one document kind that becomes prompt text rather than a
   * typed attachment, so they need the same containment the process-context
   * block gets; inlined raw, a document could close the block and pose as
   * instructions.
   */
  public static final String DOCUMENT_BLOCK_OPEN = "<document";

  /** Closing delimiter of a text document block. See {@link #DOCUMENT_BLOCK_OPEN}. */
  public static final String DOCUMENT_BLOCK_CLOSE = "</document>";

  // ── instructionMode values ────────────────────────────────────────────────

  /**
   * {@code instructionMode} value — the modeler's {@code instruction} replaces
   * the bundled default prompt entirely. Default mode for backwards compatibility.
   */
  public static final String INSTRUCTION_MODE_REPLACE = "replace";

  /**
   * {@code instructionMode} value — the modeler's {@code instruction} is
   * appended to the bundled default prompt, separated by
   * {@link #INSTRUCTION_MODE_SEPARATOR}.
   */
  public static final String INSTRUCTION_MODE_APPEND = "append";

  /**
   * {@code instructionMode} value — the modeler's {@code instruction} is
   * prepended to the bundled default prompt, separated by
   * {@link #INSTRUCTION_MODE_SEPARATOR}.
   */
  public static final String INSTRUCTION_MODE_PREPEND = "prepend";

  /** Default for {@code instructionMode} when the parameter is unset. */
  public static final String DEFAULT_INSTRUCTION_MODE = INSTRUCTION_MODE_REPLACE;

  /** Separator used between the bundled default prompt and the caller instruction. */
  public static final String INSTRUCTION_MODE_SEPARATOR = "\n\n";

  private AgentConnectorConstants() {
    // utility class
  }

}
