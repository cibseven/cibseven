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
package org.cibseven.bpm.engine.database;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts all created indexes inside each SQL file from the engine/src/main/resources/org/camunda/bpm/engine/db/create folder
 * and compares whether we have corresponding drop action for each index inside appropriate drop SQL file.
 * Folder for drop SQL files is engine/src/main/resources/org/camunda/bpm/engine/db/drop.
 *
 * Pairs are:
 * <ul>
 *   <li>engine/db/create/activiti.db2.create.case.engine.sql => engine/db/drop/activiti.db2.drop.case.engine.sql</li>
 *   <li>engine/db/create/activiti.db2.create.case.history.sql => engine/db/drop/activiti.db2.drop.case.history.sql</li>
 *   <li>and so on</li>
 * </ul>
 *
 * @author Serge Krot, CIB software GmbH
 */
public class IndexChecker {
    private final static Pattern patternCreate = Pattern.compile("^\\s*create\\s+(unique\\s+)?index\\s+(\\S+).*", Pattern.CASE_INSENSITIVE);
    private final static Pattern patternDrop   = Pattern.compile("^\\s*drop\\s+index\\s+([^.]+\\.)?([^ ;]+).*", Pattern.CASE_INSENSITIVE);

    /**
     * @param args absolute path to engine/src/main/resources/org/camunda/bpm/engine/db/create folder.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new RuntimeException("Must provide one arguments: <folder to SQL scripts>");
        }

        final String createScriptsFolder = args[0];
        final Path createDir = Paths.get(createScriptsFolder);

        try (Stream<Path> paths = Files.walk(createDir)) {
            // Filter only .sql files
            paths.filter(p -> p.toString().endsWith(".sql"))
                 .forEach(createScript -> {
                     Path dropScript = Paths.get(createScript.toString().replace("create", "drop"));
                     try {
                         // Extract created indexes
                         final Set<String> createdIndexes = extractIndexes(createScript, "create", patternCreate, 2);
                         // Extract dropped indexes
                         final Set<String> droppedIndexes = extractIndexes(dropScript, "drop", patternDrop, 2);

                         if (createdIndexes == null || droppedIndexes == null) {
                             throw new RuntimeException("Create/drop files mismatch. SQL-create file: " + createScript);
                         }

                         // Compare indexes
                         if (!createdIndexes.equals(droppedIndexes)) {
                             System.err.println("Found index difference for:");
                             System.err.println(createScript);
                             System.err.println(dropScript);
                             System.err.println("Created indexes: " + createdIndexes);
                             System.err.println("Dropped indexes: " + droppedIndexes);
                             throw new RuntimeException("Some idexes has no appropriate drop SQL statements. SQL-create file: " + createScript);
                         }
                     } catch (IOException e) {
                         throw new RuntimeException("Error processing files: " + e.getMessage());
                     }
                 });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Set<String> extractIndexes(final Path scriptPath, final String indexType, final Pattern pattern, final int valueIndex) throws IOException {

        if (!Files.exists(scriptPath)) {
            return null;
        }

        final Set<String> indexes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); // TreeSet to keep them sorted
        try (BufferedReader reader = Files.newBufferedReader(scriptPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String indexName = extractIndexName(line, pattern, valueIndex);
                if (indexName != null) {
                    indexes.add(indexName.toLowerCase());
                }
            }
        }

        return indexes;
    }

    private static String extractIndexName(final String input, final Pattern pattern, int valueIndex) {
        final Matcher matcher = pattern.matcher(input);
        if (matcher.matches()) {
            return matcher.group(valueIndex);
        }
        return null;
    }
}