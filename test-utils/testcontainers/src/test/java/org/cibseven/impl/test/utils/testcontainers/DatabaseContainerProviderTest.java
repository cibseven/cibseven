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
package org.cibseven.impl.test.utils.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

/**
 * This test should not be run on our CI, as it requires a Docker-in-Docker image to run successfully.
 */
@Disabled
public class DatabaseContainerProviderTest {

  static Stream<Arguments> scenarios() throws ParseException {
    return Stream.of(
      Arguments.of("jdbc:tc:cibpostgresql:15:///process-engine", "SELECT version();", "15."),
      Arguments.of("jdbc:tc:cibpostgresql:16:///process-engine", "SELECT version();", "16."),
      Arguments.of("jdbc:tc:cibpostgresql:17:///process-engine", "SELECT version();", "17."),
      Arguments.of("jdbc:tc:cibpostgresql:18:///process-engine", "SELECT version();", "18."),
      Arguments.of("jdbc:tc:cibmariadb:10.11://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "10.11"),
      Arguments.of("jdbc:tc:cibmariadb:11.4://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "11.4"),
      Arguments.of("jdbc:tc:cibmariadb:11.8://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "11.8"),
      Arguments.of("jdbc:tc:cibmariadb:12.3://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "12.3"),
      Arguments.of("jdbc:tc:cibmysql:8.4://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "8.4"),
      Arguments.of("jdbc:tc:cibmysql:9.7://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "9.7"),
      Arguments.of("jdbc:tc:cibsqlserver:2017-latest:///process-engine", "SELECT @@VERSION", "2017"),
      Arguments.of("jdbc:tc:cibsqlserver:2019-latest:///process-engine", "SELECT @@VERSION", "2019"),
      Arguments.of("jdbc:tc:cibsqlserver:2022-latest:///process-engine", "SELECT @@VERSION", "2022"),
      Arguments.of("jdbc:tc:cibsqlserver:2025-latest:///process-engine", "SELECT @@VERSION", "2025"),
      Arguments.of("jdbc:tc:cibdb2:11.5.9.0:///engine", "SELECT service_level FROM TABLE (sysproc.env_get_inst_info())", "v11.5"),
      Arguments.of("jdbc:tc:cibdb2:12.1.5.0:///engine", "SELECT service_level FROM TABLE (sysproc.env_get_inst_info())", "v12.1"),
      Arguments.of("jdbc:tc:ciboracle19:19.3.0.0://localhost:1521", "SELECT banner_full FROM v$version", "19."),
      Arguments.of("jdbc:tc:ciboraclefree:23.26.3-slim-faststart://localhost:1521", "SELECT banner_full FROM v$version", "26ai")
    );
  }

  @ParameterizedTest(name = "Job DueDate is set: {0}")
  @MethodSource("scenarios")
  void testJdbcTestcontainersUrl(String jdbcUrl, String versionStatement, String dbVersion) {
    // when
    try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
      connection.setAutoCommit(false);
      ResultSet rs = connection.prepareStatement(versionStatement).executeQuery();
      if (rs.next()) {
        // then
        String version = rs.getString(1);
        assertThat(version).contains(dbVersion);
      }
    } catch (SQLException throwables) {
      fail("Testcontainers failed to spin up a Docker container: " + throwables.getMessage());
    }
  }

}