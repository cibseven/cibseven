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
package org.cibseven.bpm.engine.test.api.repository.diagram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.impl.bpmn.diagram.ProcessDiagramLayoutFactory;
import org.cibseven.bpm.engine.impl.interceptor.Command;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.repository.DiagramLayout;
import org.cibseven.bpm.engine.repository.DiagramNode;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.repository.ProcessDefinitionQuery;
import org.cibseven.bpm.engine.test.ProcessEngineRule;
import org.cibseven.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;


/**
 * Tests process model and diagram retrieval features of the
 * {@link RepositoryService}.
 * 
 * This test generates HTML code containing the positions and dimensions of all
 * elements in a BPMN process and compares that to HTML files stored in
 * 'src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram'.
 * 
 * If the expected HTML code needs to be changed due to changes in
 * {@link ProcessDiagramLayoutFactory}, the files can be regenerated by running the
 * test while {@link ProcessDiagramRetrievalTest#OVERWRITE_EXPECTED_HTML_FILES}
 * is set to true.
 * 
 * @author Falko Menge
 */
@RunWith(Parameterized.class)
public class ProcessDiagramRetrievalTest {
  
  /**
   * Set this to true and run the tests to regenerate the HTML files located in
   * src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram, which
   * contain expected values for the HTML code generated by test cases.
   */
  private static final boolean OVERWRITE_EXPECTED_HTML_FILES = false;
  
  @Rule
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();

  /**
   * Provides a list of parameters for
   * {@link ProcessDiagramRetrievalTest#ProcessDiagramRetrievalTest(String, String, String, String)}
   */
  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
            { "testStartEventWithNegativeCoordinates", ".bpmn", ".png", "sid-61D1FC47-8031-4834-A9B4-84158E73F7B9" },
            { "testStartAndEndEventWithNegativeCoordinates", ".bpmn", ".png", "sid-61D1FC47-8031-4834-A9B4-84158E73F7B9" },
            { "testProcessWithTask", ".bpmn", ".png", "sid-1E142B16-AFAF-429E-A441-D1232CFBD560" },
            { "testProcessFromCamundaFoxDesigner", ".bpmn", ".png", "UserTask_1" },
            { "testProcessFromCamundaFoxDesigner", ".bpmn", ".jpg", "UserTask_1" },
            { "testProcessFromActivitiDesigner", ".bpmn20.xml", ".png", "Send_rejection_notification_via_email__3" },
            { "testSequenceFlowOutOfBounds", ".bpmn", ".png", "sid-61D1FC47-8031-4834-A9B4-84158E73F7B9" },
            { "testProcessFromAdonis", ".bpmn", ".png", "_16615" },
            { "testProcessFromIboPrometheus", ".bpmn", ".png", "ibo-5784efbe-35ac-44bc-bcbe-4c18a2f23d5d" },
            { "testProcessFromIboPrometheus", ".bpmn", ".jpg", "ibo-5784efbe-35ac-44bc-bcbe-4c18a2f23d5d" },
            { "testInvoiceProcessCamundaFoxDesigner", ".bpmn20.xml", ".jpg", "Rechnung_freigeben_125" },
            { "testInvoiceProcessSignavio", ".bpmn", ".png", "Freigebenden_zuordnen_143" },
            { "testInvoiceProcessFromBusinessProcessIncubator", ".bpmn", ".png", "Rechnung_kl_ren_148" },
            { "testProcessFromYaoqiang", ".bpmn", ".png", "_3" },
    });
  }
  
  private final String xmlFileName;
  private final String imageFileName;
  private final String highlightedActivityId;
  private RepositoryService repositoryService;
  private String deploymentId;

  private ProcessDefinitionQuery processDefinitionQuery;

  public ProcessDiagramRetrievalTest(String modelName, String xmlFileExtension, String imageFileExtension, String highlightedActivityId) {
    this.xmlFileName = modelName + xmlFileExtension;
    this.imageFileName = modelName + imageFileExtension;
    this.highlightedActivityId = highlightedActivityId;
  }

  @Before
  public void setup() {
    repositoryService = engineRule.getRepositoryService();
    deploymentId = repositoryService.createDeployment()
      .addClasspathResource("org/cibseven/bpm/engine/test/api/repository/diagram/" + xmlFileName)
      .addClasspathResource("org/cibseven/bpm/engine/test/api/repository/diagram/" + imageFileName)
      .deploy()
      .getId();
    processDefinitionQuery = repositoryService.createProcessDefinitionQuery();
  }
  
  @After
  public void teardown() {
    repositoryService.deleteDeployment(deploymentId, true);
  }

  /**
   * Tests {@link RepositoryService#getProcessModel(String)}.
   */
  @Test
  public void testGetProcessModel() throws Exception {
    if (1 == processDefinitionQuery.count()) {
      ProcessDefinition processDefinition = processDefinitionQuery.singleResult();
      InputStream expectedStream = new FileInputStream("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + xmlFileName);
      InputStream actualStream = repositoryService.getProcessModel(processDefinition.getId());
      assertTrue(isEqual(expectedStream, actualStream));
    } else {
      // some test diagrams do not contain executable processes
      // and are therefore ignored by the engine
    }
  }

  /**
   * Tests {@link RepositoryService#getProcessDiagram(String)}.
   */
  @Test
  public void testGetProcessDiagram() throws Exception {
    if (1 == processDefinitionQuery.count()) {
      ProcessDefinition processDefinition = processDefinitionQuery.singleResult();
      InputStream expectedStream = new FileInputStream("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + imageFileName);
      InputStream actualStream = repositoryService.getProcessDiagram(processDefinition.getId());
//      writeToFile(repositoryService.getProcessDiagram(processDefinition.getId()),
//              new File("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + imageFileName + ".actual.png"));
      assertTrue(isEqual(expectedStream, actualStream));
    } else {
      // some test diagrams do not contain executable processes
      // and are therefore ignored by the engine
    }
  }

  @Test
  public void testGetProcessDiagramAfterCacheWasCleaned() {
    if (1 == processDefinitionQuery.count()) {
      engineRule.getProcessEngineConfiguration().getDeploymentCache().discardProcessDefinitionCache();
      // given
      ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().singleResult();

      // when
      InputStream stream = repositoryService.getProcessDiagram(processDefinition.getId());

      // then
      assertNotNull(processDefinition.getDiagramResourceName());
      assertNotNull(stream);
    } else {
      // some test diagrams do not contain executable processes
      // and are therefore ignored by the engine
    }
  }
  
  /**
   * Tests {@link RepositoryService#getProcessDiagramLayout(String)} and
   * {@link ProcessDiagramLayoutFactory#getProcessDiagramLayout(InputStream, InputStream)}.
   */
  @Test
  public void testGetProcessDiagramLayout() throws Exception {
    DiagramLayout processDiagramLayout;
    if (1 == processDefinitionQuery.count()) {
      ProcessDefinition processDefinition = processDefinitionQuery.singleResult();
      assertNotNull(processDefinition);
      processDiagramLayout = repositoryService.getProcessDiagramLayout(processDefinition.getId());
    } else {
      // some test diagrams do not contain executable processes
      // and are therefore ignored by the engine
      final InputStream bpmnXmlStream = new FileInputStream("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + xmlFileName);
      final InputStream imageStream = new FileInputStream("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + imageFileName);

      assertNotNull(bpmnXmlStream);
      assertNotNull(imageStream);

      // we need to run this in the ProcessEngine context
      processDiagramLayout = engineRule.getProcessEngineConfiguration()
        .getCommandExecutorTxRequired()
        .execute(new Command<DiagramLayout>() {
          @Override
          public DiagramLayout execute(CommandContext commandContext) {
            return new ProcessDiagramLayoutFactory().getProcessDiagramLayout(bpmnXmlStream, imageStream);
          }
        });
    }
    assertLayoutCorrect(processDiagramLayout);
  }

  private void assertLayoutCorrect(DiagramLayout processDiagramLayout) throws IOException {
    String html = generateHtmlCode(imageFileName, processDiagramLayout, highlightedActivityId);
    
    File htmlFile = new File("src/test/resources/org/cibseven/bpm/engine/test/api/repository/diagram/" + imageFileName + ".html");
    if (OVERWRITE_EXPECTED_HTML_FILES) {
      FileUtils.writeStringToFile(htmlFile, html);
      fail("The assertions of this test only work if ProcessDiagramRetrievalTest#OVERWRITE_EXPECTED_HTML_FILES is set to false.");
    }
    assertEquals(FileUtils.readFileToString(htmlFile).replace("\r", ""), html); // remove carriage returns in case the files have been fetched via Git on Windows
  }

  private static String generateHtmlCode(String imageUrl, DiagramLayout processDiagramLayout, String highlightedActivityId) {
    StringBuilder html = new  StringBuilder();
    html.append("<!DOCTYPE html>\n");
    html.append("<html>\n");
    html.append("  <head>\n");
    html.append("    <style type=\"text/css\"><!--\n");
    html.append("      .BPMNElement {\n");
    html.append("        position: absolute;\n");
    html.append("        border: 2px dashed lightBlue;\n");
    html.append("        border-radius: 5px; -moz-border-radius: 5px;\n");
    html.append("      }\n");
    if (highlightedActivityId != null && highlightedActivityId.length() > 0) {
      html.append("      #" + highlightedActivityId + " {border: 2px solid red;}\n");
    }
    html.append("    --></style>");
    html.append("  </head>\n");
    html.append("  <body>\n");
    html.append("    <div style=\"position: relative\">\n");
    html.append("      <img src=\"" + imageUrl + "\" />\n");
    
    List<DiagramNode> nodes = new ArrayList<DiagramNode>(processDiagramLayout.getNodes());
    // sort the nodes according to their ID property.
    Collections.sort(nodes, new DiagramNodeComparator());    
    for (DiagramNode node : nodes) {
      html.append("      <div");
      html.append(" class=\"BPMNElement\"");
      html.append(" id=\"" + node.getId() + "\"");
      html.append(" style=\"");
      html.append(" left: " + (int) (node.getX() - 2) + "px;");
      html.append(" top: " + (int) (node.getY() - 2) + "px;");
      html.append(" width: " + node.getWidth().intValue() + "px;");
      html.append(" height: " + node.getHeight().intValue() + "px;\"></div>\n");
    }
    html.append("    </div>\n");
    html.append("  </body>\n");
    html.append("</html>");
    return html.toString();
  }

  private static boolean isEqual(InputStream stream1, InputStream stream2)
          throws IOException {

      ReadableByteChannel channel1 = Channels.newChannel(stream1);
      ReadableByteChannel channel2 = Channels.newChannel(stream2);

      ByteBuffer buffer1 = ByteBuffer.allocateDirect(1024);
      ByteBuffer buffer2 = ByteBuffer.allocateDirect(1024);

      try {
          while (true) {

              int bytesReadFromStream1 = channel1.read(buffer1);
              int bytesReadFromStream2 = channel2.read(buffer2);

              if (bytesReadFromStream1 == -1 || bytesReadFromStream2 == -1) return bytesReadFromStream1 == bytesReadFromStream2;

              buffer1.flip();
              buffer2.flip();

              for (int i = 0; i < Math.min(bytesReadFromStream1, bytesReadFromStream2); i++)
                  if (buffer1.get() != buffer2.get())
                      return false;

              buffer1.compact();
              buffer2.compact();
          }

      } finally {
          if (stream1 != null) stream1.close();
          if (stream2 != null) stream2.close();
      }
  }
  
  /**
   * Might be used for debugging {@link ProcessDiagramRetrievalTest#testGetProcessDiagram()}.
   */
  @SuppressWarnings("unused")
  private static void writeToFile(InputStream is, File file) throws Exception {
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
    int c;
    while((c = is.read()) != -1) {
      out.writeByte(c);
    }
    is.close();
    out.close();
  }
  
  /**
   * sorts {@link DiagramNode DiagramNodes} by ID 
   */
  public static class DiagramNodeComparator implements Comparator<DiagramNode> {

    public int compare(DiagramNode o1, DiagramNode o2) {
      if(o1.getId() == null)  {
        return 0;
      }
      return o1.getId().compareTo(o2.getId());
    }
    
  }

}
