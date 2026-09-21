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
      Arguments.of("jdbc:tc:campostgresql:13.2:///process-engine", "SELECT version();", "13.2"),
      Arguments.of("jdbc:tc:cammariadb:10.0://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "10.0"),
      Arguments.of("jdbc:tc:cammysql:5.7://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "5.7"),
      Arguments.of("jdbc:tc:cammysql:8.0://localhost:3306/process-engine?user=camunda&password=camunda", "SELECT version();", "8.0"),
      Arguments.of("jdbc:tc:camsqlserver:2017:///process-engine", "SELECT @@VERSION", "2017"),
      Arguments.of("jdbc:tc:camsqlserver:2019:///process-engine", "SELECT @@VERSION", "2019")
      // DB2 and Oracle commented out as before
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