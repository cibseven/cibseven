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
package org.cibseven.webapp.providers;

/**
 * Fixed process-instance ids, keys and business keys shared by the
 * {@code findProcessesInstancesRuntime}/{@code findProcessesInstancesHistory} tests in this module.
 * <p>
 * A twin of this class with the same constant values lives in
 * {@code cibseven-webclient-core/.../providers/ProcessInstanceRuntimeHistoryTestData.java} (as JSON
 * fixtures instead of engine mocks, since that module drives the REST-based provider). The two modules
 * cannot share a single compiled class without a cross-module test-jar dependency, so both copies are
 * kept in sync by hand - update both when either changes, so the direct and REST provider
 * implementations are exercised against the same scenario.
 */
final class ProcessInstanceRuntimeHistoryTestData {

	private ProcessInstanceRuntimeHistoryTestData() {}

	static final String INSTANCE_ID_1 = "instance-1";
	static final String INSTANCE_ID_2 = "instance-2";
	static final String PROCESS_DEFINITION_ID = "process-1";
	static final String PROCESS_DEFINITION_KEY = "processKey1";
	static final String BUSINESS_KEY_1 = "businessKey1";
	static final String BUSINESS_KEY_2 = "businessKey2";
	static final String INCIDENT_ID_1 = "incident-1";
}
