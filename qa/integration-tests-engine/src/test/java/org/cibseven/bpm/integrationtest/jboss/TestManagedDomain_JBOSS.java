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
package org.cibseven.bpm.integrationtest.jboss;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.fail;
/**
 * <p>Ensures subsystem boots in domain mode</p>
 *
 * @author Daniel Meyer
 * @author Christian Lipphardt
 *
 */
@ExtendWith(ArquillianExtension.class)
public class TestManagedDomain_JBOSS {

  @Deployment @TargetsContainer("test-domain")
  public static WebArchive create1() {
      return ShrinkWrap.create(WebArchive.class, "test.war");
  }

  @Test
  public void shouldBeAbleToLookupDefaultProcessEngine() {
    try {
    	Assertions.assertNotNull(InitialContext.doLookup("java:global/camunda-bpm-platform/process-engine/default"));
    } catch (NamingException e) {
      fail("Could not lookup default process engine");
    }

    try {
      Assertions.assertNotNull(InitialContext.doLookup("java:global/camunda-bpm-platform/process-engine/someNonExistingEngine"));
      fail("Should not be able to lookup someNonExistingEngine process engine");
    } catch (NamingException e) {
      // expected
    }
  }

}
