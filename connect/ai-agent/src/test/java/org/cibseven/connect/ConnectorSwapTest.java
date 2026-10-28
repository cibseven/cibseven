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
package org.cibseven.connect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;

import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorRequest;
import org.cibseven.connect.spi.ConnectorRequestInterceptor;
import org.cibseven.connect.spi.ConnectorResponse;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectorSwap} promises to leave the registry exactly as {@link ConnectorSwap#install}
 * found it; these tests exercise that promise directly against {@link Connectors} rather than
 * through a whole process engine, which is the only way "nothing was registered before" is
 * reachable at all — every BPMN-level test installs under {@code AgentConnector.ID}, an id the
 * classpath always has a real connector for.
 */
public class ConnectorSwapTest {

  /** Nothing on the classpath ever registers a connector under this id. */
  private static final String FRESH_ID =
      "connector-swap-test-" + ConnectorSwapTest.class.getSimpleName();

  private static final class FakeConnector
      implements Connector<ConnectorRequest<ConnectorResponse>> {
    @Override
    public String getId() {
      return FRESH_ID;
    }

    @Override
    public ConnectorRequest<ConnectorResponse> createRequest() {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }

    @Override
    public List<ConnectorRequestInterceptor> getRequestInterceptors() {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }

    @Override
    public void setRequestInterceptors(List<ConnectorRequestInterceptor> requestInterceptors) {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }

    @Override
    public Connector<ConnectorRequest<ConnectorResponse>> addRequestInterceptor(
        ConnectorRequestInterceptor interceptor) {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }

    @Override
    public Connector<ConnectorRequest<ConnectorResponse>> addRequestInterceptors(
        Collection<ConnectorRequestInterceptor> interceptors) {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }

    @Override
    public ConnectorResponse execute(ConnectorRequest<ConnectorResponse> request) {
      throw new UnsupportedOperationException("not needed: this swap is never executed");
    }
  }

  /**
   * The gap round 5 of the CIB7-2150 review found: {@code restore()} used to be a no-op when
   * nothing held the id before {@code install()}, which left the test's own connector registered
   * under that id for whatever ran next. A fresh id is what makes "nothing held it before"
   * reachable — every id this module's other tests use is either a real deployed connector
   * ({@code AgentConnector.ID}) or one some other test already restored.
   */
  @Test
  public void restoringAnIdThatHeldNothingLeavesNothingBehind() {
    Connector<?> before = Connectors.getConnector(FRESH_ID);
    assertThat(before).as("the premise: nothing is registered under this id yet").isNull();

    ConnectorSwap swap = new ConnectorSwap(FRESH_ID);
    swap.install(new FakeConnector());
    Connector<?> installed = Connectors.getConnector(FRESH_ID);
    assertThat(installed).isInstanceOf(FakeConnector.class);

    swap.restore();

    Connector<?> after = Connectors.getConnector(FRESH_ID);
    assertThat(after)
        .as("restore() must remove the id entirely, not leave the test's connector registered")
        .isNull();
  }

  /** The ordinary case: something real held the id, and must be the one left standing. */
  @Test
  public void restoringAnIdThatHeldAConnectorPutsTheOriginalBack() {
    FakeConnector original = new FakeConnector();
    Connectors.registerConnector(FRESH_ID, original);
    try {
      ConnectorSwap swap = new ConnectorSwap(FRESH_ID);
      swap.install(new FakeConnector());
      Connector<?> installed = Connectors.getConnector(FRESH_ID);
      assertThat(installed).isNotSameAs(original);

      swap.restore();

      Connector<?> after = Connectors.getConnector(FRESH_ID);
      assertThat(after)
          .as("the connector that held the id before install() must be the one restore() leaves")
          .isSameAs(original);
    } finally {
      Connectors.unregisterConnector(FRESH_ID);
    }
  }

  /**
   * A second {@code install()} on the same swap must not mistake its own first connector for the
   * original. {@code replaced == null} cannot tell "nothing was registered" apart from "I have
   * not looked yet" — {@code installed} is the field that makes the distinction, and this is the
   * one scenario that breaks without it: both installs replace "nothing", so a swap that re-read
   * the registry on the second call would capture its own first connector as if it had been the
   * original and restore that instead of removing the id.
   */
  @Test
  public void installingTwiceStillRestoresWhatWasThereBeforeTheFirstInstall() {
    ConnectorSwap swap = new ConnectorSwap(FRESH_ID);

    swap.install(new FakeConnector());
    swap.install(new FakeConnector());
    swap.restore();

    Connector<?> after = Connectors.getConnector(FRESH_ID);
    assertThat(after)
        .as("both installs replaced 'nothing', so restore() must remove the id — not reinstate"
            + " the first swap's own connector as if it had been the original")
        .isNull();
  }
}
