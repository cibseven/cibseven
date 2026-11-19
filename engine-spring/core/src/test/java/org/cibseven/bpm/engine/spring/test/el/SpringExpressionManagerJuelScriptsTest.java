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
package org.cibseven.bpm.engine.spring.test.el;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.cibseven.bpm.engine.spring.SpringExpressionManager;
import org.junit.jupiter.api.Test;

public class SpringExpressionManagerJuelScriptsTest {

  @Test
  public void shouldNotApplyStockExpressionManagerToJuelScripts() {
    assertThat(new SpringExpressionManager(null).isAppliedToJuelScripts()).isFalse();
  }

  @Test
  public void shouldApplyExpressionManagerWithLimitedBeansToJuelScripts() {
    assertThat(new SpringExpressionManager(null, Collections.emptyMap()).isAppliedToJuelScripts()).isTrue();
  }

  @Test
  public void shouldApplySubclassToJuelScripts() {
    assertThat(new SpringExpressionManager(null) { }.isAppliedToJuelScripts()).isTrue();
  }
}
