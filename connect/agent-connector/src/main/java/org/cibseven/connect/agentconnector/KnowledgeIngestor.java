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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

/**
 * Standalone CLI tool for ingesting documents into the pgvector knowledge base.
 *
 * <p>Run via Maven:
 * <pre>
 * mvn exec:java -Dexec.args="--file knowledge-base.pdf \
 *   --pgHost localhost --pgDatabase postgres \
 *   --pgUser my_user --pgPassword my_password"
 * </pre>
 *
 * <p>Supported arguments:
 * <ul>
 *   <li>{@code --file} (required) — path to a PDF or text file</li>
 *   <li>{@code --pgHost} (required) — PostgreSQL host</li>
 *   <li>{@code --pgPort} (optional, default 5432)</li>
 *   <li>{@code --pgDatabase} (optional, default "postgres")</li>
 *   <li>{@code --pgUser} (required)</li>
 *   <li>{@code --pgPassword} (required)</li>
 *   <li>{@code --pgTable} (optional, default "langchain4j_embeddings")</li>
 *   <li>{@code --chunkSize} (optional, default 500)</li>
 *   <li>{@code --chunkOverlap} (optional, default 50)</li>
 * </ul>
 */
public class KnowledgeIngestor {

  public static void main(String[] args) {
    Map<String, String> params = parseArgs(args);

    String file     = require(params, "--file");
    String pgHost   = require(params, "--pgHost");
    String pgUser   = require(params, "--pgUser");
    String pgPass   = require(params, "--pgPassword");
    String pgPort   = params.getOrDefault("--pgPort",    AgentConnectorConstants.DEFAULT_PG_PORT);
    String pgDb     = params.getOrDefault("--pgDatabase", "postgres");
    String pgTable  = params.getOrDefault("--pgTable",   AgentConnectorConstants.DEFAULT_PG_TABLE);
    int chunkSize   = Integer.parseInt(params.getOrDefault("--chunkSize",
        String.valueOf(AgentConnectorConstants.DEFAULT_CHUNK_SIZE)));
    int chunkOverlap = Integer.parseInt(params.getOrDefault("--chunkOverlap",
        String.valueOf(AgentConnectorConstants.DEFAULT_CHUNK_OVERLAP)));

    System.out.println("Loading document: " + file);
    Document document = FileSystemDocumentLoader.loadDocument(
        Path.of(file),
        new ApachePdfBoxDocumentParser());

    var embeddingModel = new AllMiniLmL6V2EmbeddingModel();

    var embeddingStore = PgVectorEmbeddingStore.builder()
        .host(pgHost)
        .port(Integer.parseInt(pgPort))
        .database(pgDb)
        .user(pgUser)
        .password(pgPass)
        .table(pgTable)
        .dimension(384)
        .createTable(true)
        .useIndex(true)
        .indexListSize(100)
        .build();

    var segments = DocumentSplitters.recursive(chunkSize, chunkOverlap).split(document);
    var embeddings = embeddingModel.embedAll(segments).content();
    embeddingStore.addAll(embeddings, segments);

    System.out.println("Ingestion complete: " + segments.size() + " chunks stored in table: " + pgTable);
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < args.length - 1; i += 2) {
      map.put(args[i], args[i + 1]);
    }
    return map;
  }

  private static String require(Map<String, String> params, String key) {
    String value = params.get(key);
    if (value == null || value.isEmpty()) {
      System.err.println("Missing required argument: " + key);
      System.exit(1);
    }
    return value;
  }

}
