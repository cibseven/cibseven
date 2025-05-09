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
package org.cibseven.bpm.webapp.webjar;

/**
 * This is a dummy class and should not be used anywhere.
 * It has been created solely to enable the creation of -sources.jar and -javadoc.jar files
 * during the Maven Central deployment process. Without this class, the deployment fails
 * with the following error message:
 *
 * Deployment 91e65997-8d5e-48b2-ae98-a76c8fbb3beb failed
 * pkg:maven/org.cibseven.bpm.webapp/cibseven-webapp-webjar@2.0.0:
 *  - Javadocs must be provided but not found in entries
 *  - Sources must be provided but not found in entries
 */
public class Webjar {
}
