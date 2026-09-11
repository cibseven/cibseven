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
package org.cibseven.bpm.integrationtest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;

import org.cibseven.bpm.BpmPlatform;
import org.cibseven.bpm.ProcessApplicationService;
import org.cibseven.bpm.application.ProcessApplicationInfo;
import org.cibseven.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


/**
 * @author Daniel Meyer
 *
 */
@ExtendWith(ArquillianExtension.class)
public class ProcessApplicationServiceTest extends AbstractFoxPlatformIntegrationTest {
  
  @Deployment(name="test1")
  public static WebArchive app1() {    
    return initWebArchiveDeployment("test1.war")
            .addAsResource("org/cibseven/bpm/integrationtest/testDeployProcessArchive.bpmn20.xml");
  }
  
  @Deployment(name="test2")
  public static WebArchive app2() {    
    return initWebArchiveDeployment("test2.war")
            .addAsResource("org/cibseven/bpm/integrationtest/testDeployProcessArchiveWithoutActivitiCdi.bpmn20.xml");
  }
  
  @Test
  @OperateOnDeployment("test1")
  public void testProcessApplicationsDeployed() {
    
    ProcessApplicationService processApplicationService = BpmPlatform.getProcessApplicationService();
    
    Set<String> processApplicationNames = processApplicationService.getProcessApplicationNames();

    // check if the new applications are deployed with allowed names
    processApplicationNames.retainAll(Arrays.asList(new String [] {"test1", "test2", "/test1", "/test2"}));

    assertThat(processApplicationNames.size()).isEqualTo(2);

    for (String appName : processApplicationNames) {
      ProcessApplicationInfo processApplicationInfo = processApplicationService.getProcessApplicationInfo(appName);
      
      Assertions.assertNotNull(processApplicationInfo);
      Assertions.assertNotNull(processApplicationInfo.getName());
      assertThat(processApplicationInfo.getDeploymentInfo().size()).isEqualTo(1);      
    }
    
  }


}
