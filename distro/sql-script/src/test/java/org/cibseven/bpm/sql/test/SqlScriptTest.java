/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
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
package org.cibseven.bpm.sql.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.change.Change;
import liquibase.change.core.SQLFileChange;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.DiffResult;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.ObjectChangeFilter;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.UniqueConstraint;
import org.cibseven.commons.utils.IoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SqlScriptTest {

  /*
   * The following unique constraints are present on both databases (manual and
   * by changelog) but are not reliably contained in database snapshots created
   * by Liquibase#diff. They have been manually confirmed and can be ignored in
   * the comparison in case they are missing in either database.
   */
  protected static final List<String> IGNORED_CONSTRAINTS = Arrays.asList(
      "ACT_UNIQ_VARIABLE",
      "CONSTRAINT_8D1",// used on all but PostgreSQL
      "ACT_HI_PROCINST_PROC_INST_ID__KEY",// used on PostgreSQL
      "ACT_UNIQ_TENANT_MEMB_USER",
      "ACT_UNIQ_TENANT_MEMB_GROUP");

  /** The last Camunda release, and the point from which the CIB seven changeSets take over. */
  protected static final String CAMUNDA_FINAL_TAG = "7.24.0";

  /** Where Camunda recorded its Liquibase history - a different path, so the ids no longer match. */
  protected static final String CAMUNDA_HISTORY_PATH = "org/camunda/bpm/engine/db/liquibase/camunda-changelog.xml";

  protected Properties properties;
  protected Database database;
  protected String databaseType;
  protected String projectVersion;
  protected Liquibase liquibase;
  protected DiffGeneratorFactory databaseDiffer;

  @BeforeEach
  public void setup() throws Exception {
    InputStream is = getClass().getClassLoader().getResourceAsStream("properties-from-pom.properties");
    properties = new Properties();
    properties.load(is);

    databaseType = properties.getProperty("database.type");
    projectVersion = properties.getProperty("project.version");

    database = getDatabase();
    liquibase = getLiquibase();
    databaseDiffer = DiffGeneratorFactory.getInstance();
    cleanUpDatabaseTables();
  }

  @AfterEach
  public void tearDown() throws Exception {
    cleanUpDatabaseTables();
    liquibase.close();
  }

  @Test
  public void shouldEqualLiquibaseChangelogAndCreateScripts() throws Exception {
    // given
    executeSqlScript("create", "engine");
    executeSqlScript("create", "identity");
    DatabaseSnapshot snapshotScripts = createCurrentDatabaseSnapshot();
    cleanUpDatabaseTables();

    // when set up with Liquibase changelog
    liquibase.update(new Contexts());

    // then
    DatabaseSnapshot snapshotLiquibase = createCurrentDatabaseSnapshot();
    DiffResult diffResult = databaseDiffer.compare(snapshotScripts, snapshotLiquibase, new CompareControl());
    List<ChangeSet> changeSetsToApply = new DiffToChangeLog(diffResult, new CustomDiffOutputControl())
        .generateChangeSets();

    assertThat(changeSetsToApply)
        .withFailMessage("Liquibase database schema misses changes: %s", getChanges(changeSetsToApply))
        .isEmpty();
  }

  @Test
  public void shouldEqualOldUpgradedAndNewCreatedViaLiquibase() throws Exception {
    try (Liquibase liquibaseOld = getLiquibase("scripts-old/", getDatabase())) {
      // given
      liquibase.update(new Contexts());
      DatabaseSnapshot snapshotCurrent = createCurrentDatabaseSnapshot();
      cleanUpDatabaseTables();

      // old changelog executed
      liquibaseOld.update(new Contexts());

      // when new changelog executed afterward
      liquibase.update(new Contexts());

      // then
      DatabaseSnapshot snapshotUpgraded = createCurrentDatabaseSnapshot();
      DiffResult diffResult = databaseDiffer.compare(snapshotCurrent, snapshotUpgraded, new CompareControl());
      List<ChangeSet> changeSetsToApply = new DiffToChangeLog(diffResult, new DiffOutputControl()).generateChangeSets();

      assertThat(changeSetsToApply)
          .withFailMessage("Resulting upgraded database misses changes: %s", getChanges(changeSetsToApply))
          .isEmpty();
    }
  }

  @Test
  public void shouldEqualOldUpgradedAndNewCreatedViaScripts() throws Exception {
    // given
    String currentMajorMinor = properties.getProperty("current.majorminor");
    String oldMajorMinor = properties.getProperty("old.majorminor");

    executeSqlScript("create", "engine");
    executeSqlScript("create", "identity");
    DatabaseSnapshot snapshotCurrent = createCurrentDatabaseSnapshot();

    cleanUpDatabaseTables();

    // old CREATE scripts executed
    executeSqlScript("scripts-old/", "create", "engine_" + oldMajorMinor + ".0");
    executeSqlScript("scripts-old/", "create", "identity_" + oldMajorMinor + ".0");

    // when UPGRADE scripts executed
    executeSqlScript("local-upgrade-test/", "upgrade", "engine_" + oldMajorMinor + "_patch");
    executeSqlScript("local-upgrade-test/", "upgrade", "engine_" + oldMajorMinor + "_to_" + currentMajorMinor);
    executeSqlScript("local-upgrade-test/", "upgrade", "engine_" + currentMajorMinor + "_patch");

    // then
    DatabaseSnapshot snapshotUpgraded = createCurrentDatabaseSnapshot();
    DiffResult diffResult = databaseDiffer.compare(snapshotCurrent, snapshotUpgraded, new CompareControl());
    List<ChangeSet> changeSetsToApply = new DiffToChangeLog(diffResult, new CustomDiffOutputControl())
        .generateChangeSets();

    assertThat(changeSetsToApply)
        .withFailMessage("Resulting upgraded database schema differs: %s", getChanges(changeSetsToApply))
        .isEmpty();
  }

  /**
   * A database that still carries the Liquibase history written by Camunda is the situation
   * every migrating customer is in: the changeSets are identical but were recorded under the
   * Camunda classpath, so they do not match by (id, author, filename) any more.
   */
  @Test
  public void shouldMigrateDatabaseWithCamundaLiquibaseHistory() throws Exception {
    // given the schema a clean installation produces
    liquibase.update(new Contexts());
    DatabaseSnapshot reference = createCurrentDatabaseSnapshot();
    cleanUpDatabaseTables();

    // and a Camunda 7.24 database whose history was recorded under the Camunda path
    liquibase.update(CAMUNDA_FINAL_TAG, new Contexts());
    renameChangeLogHistoryTo(CAMUNDA_HISTORY_PATH);

    // when the CIB seven changelog is applied to it
    liquibase.update(new Contexts());

    // then the Camunda steps were skipped and only the CIB seven steps ran
    Map<String, String> execTypes = execTypesOfNewlyEvaluatedChangeSets();
    assertThat(execTypes).containsAllEntriesOf(expectedExecTypes());

    // and the result is the same schema a clean installation produces
    assertSchemaMatches(reference);
  }

  /**
   * Same as above, but the database was installed fresh at Camunda 7.24 instead of being
   * upgraded to it. Its ACT_GE_SCHEMA_LOG therefore holds a single row and none of the
   * intermediate versions - a step must not run just because its target version is absent.
   */
  @Test
  public void shouldMigrateFreshCamundaInstallWithSparseSchemaLog() throws Exception {
    // given the schema a clean installation produces
    liquibase.update(new Contexts());
    DatabaseSnapshot reference = createCurrentDatabaseSnapshot();
    cleanUpDatabaseTables();

    // and a Camunda 7.24 database that was never upgraded, so it logged only its own version
    liquibase.update(CAMUNDA_FINAL_TAG, new Contexts());
    collapseSchemaLogToSingleRow(CAMUNDA_FINAL_TAG);
    renameChangeLogHistoryTo(CAMUNDA_HISTORY_PATH);

    // when the CIB seven changelog is applied to it
    liquibase.update(new Contexts());

    // then the Camunda steps were still skipped - none ran against the newer schema
    Map<String, String> execTypes = execTypesOfNewlyEvaluatedChangeSets();
    assertThat(execTypes).containsAllEntriesOf(expectedExecTypes());

    assertSchemaMatches(reference);
  }

  /**
   * ACT_RE_CAMFORMDEF is created by the 7.15-to-7.16 step and never dropped, so a schema
   * without it predates Camunda 7.16. This changelog cannot migrate such a database and
   * must refuse it instead of silently applying only the CIB seven steps.
   */
  @Test
  public void shouldRejectSchemaOlderThanCamunda716() throws Exception {
    // given a Camunda managed schema that looks older than Camunda 7.16
    liquibase.update(CAMUNDA_FINAL_TAG, new Contexts());
    executeSql("DROP TABLE ACT_RE_CAMFORMDEF");
    renameChangeLogHistoryTo(CAMUNDA_HISTORY_PATH);

    // when
    Throwable thrown = catchThrowable(() -> liquibase.update(new Contexts()));

    // then the update is refused, with the reason stated
    assertThat(thrown)
        .withFailMessage("Expected the changelog to reject a pre-7.16 schema, but the update succeeded")
        .isNotNull();
    assertThat(causeChainMessage(thrown)).contains("older than Camunda 7.16");
  }

  /** Camunda-era steps must be skipped, CIB seven steps must run. */
  protected static Map<String, String> expectedExecTypes() {
    Map<String, String> expected = new LinkedHashMap<>();
    for (String id : new String[] { "7.16.0-baseline", "7.16-to-7.17", "7.17-to-7.18", "7.18-to-7.19",
        "7.19-to-7.20", "7.20-to-7.21", "7.21-to-7.22", "7.22-to-7.23", "7.23-to-7.24" }) {
      expected.put(id, "MARK_RAN");
    }
    for (String id : new String[] { "7.24-to-2.1", "2.1-to-2.2", "2.2-to-3.0" }) {
      expected.put(id, "EXECUTED");
    }
    return expected;
  }

  protected void assertSchemaMatches(DatabaseSnapshot reference) throws Exception {
    DatabaseSnapshot migrated = createCurrentDatabaseSnapshot();
    DiffResult diffResult = databaseDiffer.compare(reference, migrated, new CompareControl());
    List<ChangeSet> changeSetsToApply = new DiffToChangeLog(diffResult, new CustomDiffOutputControl())
        .generateChangeSets();

    assertThat(changeSetsToApply)
        .withFailMessage("Migrated database schema differs: %s", getChanges(changeSetsToApply))
        .isEmpty();
  }

  /** Pretends the existing history was written by Camunda, under its own classpath location. */
  protected void renameChangeLogHistoryTo(String fileName) throws Exception {
    executeSql(String.format("UPDATE %s SET FILENAME = '%s'", changeLogTable(), fileName));

    // without this the changeSets would still match their own rows, every step would simply be
    // skipped, and the test would pass without ever exercising the preconditions
    assertThat(queryColumn("SELECT DISTINCT FILENAME FROM " + changeLogTable()))
        .withFailMessage("Renaming the Liquibase history had no effect")
        .containsExactly(fileName);
  }

  /**
   * Reduces ACT_GE_SCHEMA_LOG to the single row a fresh installation writes, relabelled to the
   * given version. Reuses the existing row so no database specific timestamp literal is needed.
   */
  protected void collapseSchemaLogToSingleRow(String version) throws Exception {
    executeSql("DELETE FROM ACT_GE_SCHEMA_LOG WHERE ID_ <> '0'");
    executeSql(String.format("UPDATE ACT_GE_SCHEMA_LOG SET VERSION_ = '%s' WHERE ID_ = '0'", version));

    assertThat(queryColumn("SELECT VERSION_ FROM ACT_GE_SCHEMA_LOG"))
        .withFailMessage("Collapsing ACT_GE_SCHEMA_LOG had no effect, so the log is not sparse")
        .containsExactly(version);
  }

  protected List<String> queryColumn(String sql) throws Exception {
    List<String> values = new ArrayList<>();
    Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
    try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  /** EXECUTED / MARK_RAN per changeSet, for the rows this changelog has just written. */
  protected Map<String, String> execTypesOfNewlyEvaluatedChangeSets() throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    String sql = String.format("SELECT ID, EXECTYPE FROM %s WHERE FILENAME <> '%s'",
        changeLogTable(), CAMUNDA_HISTORY_PATH);
    Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
    try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        result.put(rs.getString(1), rs.getString(2));
      }
    }
    return result;
  }

  protected String changeLogTable() {
    return database.escapeTableName(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName(),
        database.getDatabaseChangeLogTableName());
  }

  protected void executeSql(String sql) throws LiquibaseException {
    SQLFileChange sqlFileChange = new SQLFileChange();
    sqlFileChange.setSql(sql);
    database.execute(sqlFileChange.generateStatements(database), null);
    // Liquibase runs with auto commit disabled, so DML would be rolled back by the next
    // operation. DDL escapes this because most databases commit it implicitly.
    database.commit();
  }

  protected static String causeChainMessage(Throwable throwable) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }

  protected void executeSqlScript(String sqlFolder, String sqlScript) throws LiquibaseException {
    executeSqlScript("", sqlFolder, sqlScript + "_" + projectVersion);
  }

  protected void executeSqlScript(String baseDirectory, String sqlFolder, String sqlScript) throws LiquibaseException {
    String scriptFileName = String.format("%ssql/%s/%s_%s.sql", baseDirectory, sqlFolder, databaseType, sqlScript);
    String statements = IoUtil.inputStreamAsString(getClass().getClassLoader().getResourceAsStream(scriptFileName));
    SQLFileChange sqlFileChange = new SQLFileChange();
    sqlFileChange.setSql(statements);
    database.execute(sqlFileChange.generateStatements(database), null);
  }

  protected void cleanUpDatabaseTables() {
    try {
      liquibase.dropAll();
      // dropAll can be incomplete if it takes too long, second attempt should
      // clean up leftovers
      liquibase.dropAll();
    } catch (Exception e) {
      // ignored
    }
  }

  protected Database getDatabase() throws DatabaseException {
    String databaseUrl = properties.getProperty("database.url");
    String databaseUser = properties.getProperty("database.username");
    String databasePassword = properties.getProperty("database.password");
    String databaseClass = properties.getProperty("database.driver");
    // Properties files don't decode XML entities, but database URLs from
    // database/pom.xml use &amp; encoding (intended for XML resource filtering).
    // Unescape so the JDBC URL contains proper '&' characters.
    if (databaseUrl != null) {
      databaseUrl = databaseUrl.replace("&amp;", "&");
    }
    // Testcontainers jdbc:tc: URLs require the Testcontainers JDBC driver
    if (databaseUrl != null && databaseUrl.startsWith("jdbc:tc:")) {
      databaseClass = "org.testcontainers.jdbc.ContainerDatabaseDriver";
    }
    return DatabaseFactory.getInstance().openDatabase(databaseUrl, databaseUser, databasePassword, databaseClass, null,
        null, null, new ClassLoaderResourceAccessor());
  }

  protected Liquibase getLiquibase() throws URISyntaxException {
    return getLiquibase("", database);
  }

  protected static Liquibase getLiquibase(String baseDirectory, Database database) throws URISyntaxException {
    return new Liquibase("camunda-changelog.xml", getAccessorForChangelogDirectory(baseDirectory), database);
  }

  protected static FileSystemResourceAccessor getAccessorForChangelogDirectory(String baseDirectory) throws URISyntaxException {
    URI changelogUri = SqlScriptTest.class.getClassLoader().getResource(baseDirectory + "sql/liquibase").toURI();
    return new FileSystemResourceAccessor(Paths.get(changelogUri).toAbsolutePath().toFile());
  }

  protected DatabaseSnapshot createCurrentDatabaseSnapshot() throws Exception {
    return SnapshotGeneratorFactory.getInstance()
        .createSnapshot(database.getDefaultSchema(), database, new SnapshotControl(database));
  }

  protected static List<String> getChanges(List<ChangeSet> changeSetsToApply) {
    return changeSetsToApply.stream()
        .flatMap(cs -> cs.getChanges().stream())
        .map(Change::getDescription)
        .collect(Collectors.toList());
  }

  protected static class CustomDiffOutputControl extends DiffOutputControl {

    public CustomDiffOutputControl() {
      setObjectChangeFilter(new IgnoreUniqueConstraintsChangeFilter());
    }

    private static class IgnoreUniqueConstraintsChangeFilter implements ObjectChangeFilter {

      @Override
      public boolean includeUnexpected(DatabaseObject object, Database referenceDatabase,
          Database comparisionDatabase) {
        return include(object);
      }

      @Override
      public boolean includeMissing(DatabaseObject object, Database referenceDatabase, Database comparisionDatabase) {
        return include(object);
      }

      @Override
      public boolean includeChanged(DatabaseObject object,
          ObjectDifferences differences,
          Database referenceDatabase,
          Database comparisionDatabase) {
        return include(object);
      }

      @Override
      public boolean include(DatabaseObject object) {
        if (object instanceof UniqueConstraint && IGNORED_CONSTRAINTS.contains(object.getName().toUpperCase())) {
          return false;
        }
        return true;
      }
    }
  }
}
