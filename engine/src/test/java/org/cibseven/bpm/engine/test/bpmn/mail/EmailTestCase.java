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
package org.cibseven.bpm.engine.test.bpmn.mail;

import java.io.IOException;
import java.net.ServerSocket;

import org.cibseven.bpm.engine.impl.test.TestLogger;
import org.cibseven.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.subethamail.wiser.Wiser;


/**
 * @author Joram Barrez
 */
public abstract class EmailTestCase extends PluggableProcessEngineTest {

  private final static Logger LOG = TestLogger.TEST_LOGGER.getLogger();

  protected Wiser wiser;

  // the configured port, restored after the test
  private int configuredMailServerPort;

  @BeforeEach
  public void setUp() throws Exception {
    configuredMailServerPort = processEngineConfiguration.getMailServerPort();

    // a free port instead of the fixed configured one: tests running in parallel JVMs (surefire forks) must not
    // send their mails to the mail server of another JVM
    int attempts = 0;
    while (wiser == null) {
      int port = findFreePort();
      Wiser server = new Wiser();
      server.setPort(port);
      try {
        LOG.info("Starting Wiser mail server on port: " + port);
        server.start();
        wiser = server;
        processEngineConfiguration.setMailServerPort(port);
        LOG.info("Wiser mail server listening on port: " + port);
      } catch (RuntimeException e) {
        // the port was taken between findFreePort and start: try another one
        if (++attempts >= 10) {
          throw e;
        }
      }
    }
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (wiser != null) {
      wiser.stop();
      wiser = null;
    }
    processEngineConfiguration.setMailServerPort(configuredMailServerPort);
  }

  protected static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

}
