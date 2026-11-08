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
package org.cibseven.bpm.client.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.cibseven.bpm.client.task.OrderingConfig.Direction.ASC;
import static org.cibseven.bpm.client.task.OrderingConfig.Direction.DESC;
import static org.cibseven.bpm.client.task.OrderingConfig.SortingField.CREATE_TIME;

import org.cibseven.bpm.client.exception.ExternalTaskClientException;
import org.junit.jupiter.api.Test;

public class OrderingConfigTest {

  @Test
  public void shouldSortByOneFieldOnce() {
    // given
    OrderingConfig config = OrderingConfig.empty();

    // when
    config.configureField(CREATE_TIME);
    config.configureDirectionOnLastField(DESC);

    // then
    config.validateOrderingProperties();
    assertThat(config.toSortingDtos())
        .extracting(SortingDto::getSortBy, SortingDto::getSortOrder)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("createTime", "desc"));
  }

  @Test
  public void shouldKeepFieldsConfiguredTwice() {
    // given
    OrderingConfig config = OrderingConfig.empty();

    // when
    config.configureField(CREATE_TIME);
    config.configureDirectionOnLastField(DESC);
    config.configureField(CREATE_TIME);
    config.configureDirectionOnLastField(ASC);

    // then
    assertThat(config.toSortingDtos())
        .extracting(SortingDto::getSortBy, SortingDto::getSortOrder)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("createTime", "desc"),
            org.assertj.core.groups.Tuple.tuple("createTime", "asc"));
  }

  @Test
  public void shouldBeEmptyWithoutOrdering() {
    OrderingConfig config = OrderingConfig.empty();

    config.validateOrderingProperties();
    assertThat(config.toSortingDtos()).isEmpty();
  }

  @Test
  public void shouldRejectMissingDirection() {
    OrderingConfig config = OrderingConfig.empty();
    config.configureField(CREATE_TIME);

    assertThatThrownBy(config::validateOrderingProperties).isInstanceOf(ExternalTaskClientException.class);
  }

  @Test
  public void shouldRejectSecondDirection() {
    OrderingConfig config = OrderingConfig.empty();
    config.configureField(CREATE_TIME);
    config.configureDirectionOnLastField(DESC);

    assertThatThrownBy(() -> config.configureDirectionOnLastField(ASC)).isInstanceOf(ExternalTaskClientException.class);
  }

  @Test
  public void shouldRejectDirectionWithoutField() {
    OrderingConfig config = OrderingConfig.empty();

    assertThatThrownBy(() -> config.configureDirectionOnLastField(ASC)).isInstanceOf(ExternalTaskClientException.class);
  }

}
