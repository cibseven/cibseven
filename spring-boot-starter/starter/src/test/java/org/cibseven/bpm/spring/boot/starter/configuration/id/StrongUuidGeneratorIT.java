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
package org.cibseven.bpm.spring.boot.starter.configuration.id;


import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.IdGenerator;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.cibseven.bpm.spring.boot.starter.test.nonpa.TestApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StrongUuidGenerator is the default one.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = { TestApplication.class })
public class StrongUuidGeneratorIT {

  @Autowired
  private IdGenerator idGenerator;

  @Autowired
  private ProcessEngine processEngine;

  @Test
  public void configured_idGenerator_is_uuid() throws Exception {
    IdGenerator idGenerator = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration()).getIdGenerator();

    assertThat(idGenerator).isOfAnyClassIn(StrongUuidGenerator.class);
  }

  @Test
  public void nextId_is_uuid() throws Exception {
    assertThat(idGenerator.getNextId().split("-")).hasSize(5);
  }
}
