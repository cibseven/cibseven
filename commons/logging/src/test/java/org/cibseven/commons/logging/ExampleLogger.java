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
package org.cibseven.commons.logging;

/**
 * @author Daniel Meyer
 *
 */
public class ExampleLogger extends BaseLogger {

  public static final String COMPONENT_ID = "01";

  public static final String NAME = "org.cibseven.commons.logging";

  public static final String PROJECT_CODE = "TEST";

  public static ExampleLogger LOG = createLogger(ExampleLogger.class, PROJECT_CODE, NAME, COMPONENT_ID);

}