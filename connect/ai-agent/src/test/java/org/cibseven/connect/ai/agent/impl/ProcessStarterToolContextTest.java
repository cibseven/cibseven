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
import static org.mockito.Mockito.mock;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProcessStarterToolContext#snapshot()} / {@link ProcessStarterToolContext#restore}: a
 * nested connector call (CIB7-2155) must be able to put the caller's engine, authentication and
 * audit listener back exactly as they were, rather than wiping them with {@link
 * ProcessStarterToolContext#clear()}.
 */
public class ProcessStarterToolContextTest {

  @AfterEach
  public void tearDown() {
    ProcessStarterToolContext.clear();
  }

  @Test
  public void restoreShouldPutBackTheEngineAndAuthenticationAPreviousCallHad() {
    ProcessEngine outerEngine = mock(ProcessEngine.class);
    Authentication outerAuth = new Authentication("alice", java.util.List.of("admins"));
    ProcessStarterToolContext.setEngine(outerEngine);
    ProcessStarterToolContext.setAuthentication(outerAuth);

    ProcessStarterToolContext.Snapshot outerSnapshot = ProcessStarterToolContext.snapshot();

    // A nested call overwrites both, the way AgentConnectorImpl.execute() does on re-entry.
    ProcessEngine innerEngine = mock(ProcessEngine.class);
    Authentication innerAuth = new Authentication("bob", java.util.List.of());
    ProcessStarterToolContext.setEngine(innerEngine);
    ProcessStarterToolContext.setAuthentication(innerAuth);
    assertThat(ProcessStarterToolContext.getEngine()).isSameAs(innerEngine);

    ProcessStarterToolContext.restore(outerSnapshot);

    assertThat(ProcessStarterToolContext.getEngine()).isSameAs(outerEngine);
    assertThat(ProcessStarterToolContext.getAuthentication()).isSameAs(outerAuth);
  }

  @Test
  public void restoreShouldClearWhatWasNotSetWhenTheSnapshotWasTaken() {
    // Nothing set yet: the snapshot captures "empty".
    ProcessStarterToolContext.Snapshot emptySnapshot = ProcessStarterToolContext.snapshot();

    ProcessStarterToolContext.setEngine(mock(ProcessEngine.class));
    ProcessStarterToolContext.setAuthentication(new Authentication("carol", java.util.List.of()));

    ProcessStarterToolContext.restore(emptySnapshot);

    assertThat(ProcessStarterToolContext.getEngine()).isNull();
    assertThat(ProcessStarterToolContext.getAuthentication()).isNull();
  }

  @Test
  public void restoreShouldPutBackTheListenerAPreviousCallHad() {
    // The third value the snapshot carries, alongside the engine and the authentication: the
    // audit listener a nested connector call (CIB7-2155) must not leave pointing at its own,
    // already-finished call once it hands the thread back to its caller.
    AgentChatListener outerListener = new AgentChatListener();
    ProcessStarterToolContext.setActiveListener(outerListener);

    ProcessStarterToolContext.Snapshot outerSnapshot = ProcessStarterToolContext.snapshot();

    AgentChatListener innerListener = new AgentChatListener();
    ProcessStarterToolContext.setActiveListener(innerListener);
    assertThat(ProcessStarterToolContext.getActiveListener()).isSameAs(innerListener);

    ProcessStarterToolContext.restore(outerSnapshot);

    assertThat(ProcessStarterToolContext.getActiveListener()).isSameAs(outerListener);
  }
}
