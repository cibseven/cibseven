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
package org.cibseven.bpm.client.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.cibseven.bpm.client.ExternalTaskClient;
import org.cibseven.bpm.client.exception.ExternalTaskClientException;
import org.cibseven.bpm.client.impl.JacksonVariantCheck;
import org.junit.jupiter.api.Test;

/**
 * Both real client artifacts on the class path, as in an application that depends on both: the client refuses
 * to start instead of mixing classes of the Jackson 3 and the Jackson 2 variant.
 */
public class BothClientVariantsTest {

  @Test
  public void shouldHaveTheMarkersOfBothArtifactsOnTheClassPath() throws Exception {
    ClassLoader classLoader = getClass().getClassLoader();

    List<URL> jackson3 = Collections.list(classLoader.getResources(JacksonVariantCheck.JACKSON3_MARKER));
    List<URL> jackson2 = Collections.list(classLoader.getResources(JacksonVariantCheck.JACKSON2_MARKER));

    assertThat(jackson3).singleElement().asString().contains("cibseven-external-task-client");
    assertThat(jackson2).singleElement().asString().contains("cibseven-external-task-client-jackson2");
  }

  @Test
  public void shouldRefuseToBuildAClient() {
    assertThatThrownBy(() -> ExternalTaskClient.create().baseUrl("http://localhost:8080/engine-rest").build())
        .isInstanceOf(ExternalTaskClientException.class)
        .hasMessageContaining("Both cibseven-external-task-client (Jackson 3")
        .hasMessageContaining("keep exactly one of them");
  }
}
