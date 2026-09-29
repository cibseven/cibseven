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
package org.cibseven.bpm.util;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

/**
 * Allows to take screenshots in case of a selenium test error.
 */
public class SeleniumScreenshotRule implements TestWatcher, BeforeEachCallback {

  private static final String OUTPUT_DIR_PROPERTY_NAME = "selenium.screenshot.directory";

  private static Logger log = Logger.getAnonymousLogger();

  protected WebDriver webDriver;

  public SeleniumScreenshotRule(WebDriver driver) {
    if (driver instanceof RemoteWebDriver) {
      webDriver = new Augmenter().augment(driver);
    } else {
      webDriver = driver;
    }
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    log.info("Test failed. Attempting to create a screenshot.");
    captureScreenShot(context);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // No-op, but required for interface
  }

  public void captureScreenShot(ExtensionContext context) {
    String screenshotDirectory = System.getProperty(OUTPUT_DIR_PROPERTY_NAME);

    if (screenshotDirectory == null) {
      log.info("No screenshot created, because no output directory is specified. "
          + "Set the system property " + OUTPUT_DIR_PROPERTY_NAME + " to resolve this.");
      return;
    }

    File scrFile = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.FILE);
    String now = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    String scrFilename = context.getRequiredTestClass().getSimpleName() + "-" + context.getRequiredTestMethod().getName() + "-" + now + ".png";
    File outputFile = new File(screenshotDirectory, scrFilename);

    try {
      FileUtils.copyFile(scrFile, outputFile);
      log.info("Screenshot created: " + outputFile.getAbsolutePath());
    } catch (IOException e) {
      log.warning("Failed to create screenshot: " + e.getMessage());
    }
  }
}