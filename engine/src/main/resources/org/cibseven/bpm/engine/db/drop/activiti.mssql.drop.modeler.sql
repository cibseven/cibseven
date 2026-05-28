--
-- Copyright CIB software GmbH and/or licensed to CIB software GmbH
-- under one or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information regarding copyright
-- ownership. CIB software licenses this file to you under the Apache License,
-- Version 2.0; you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

DROP INDEX CHAT_IDX_MESSAGES_ROOM_EL_CREATED ON CHAT_MESSAGES;
DROP INDEX CHAT_IDX_MESSAGES_ROOM_CREATED ON CHAT_MESSAGES;
DROP TABLE CHAT_MESSAGES;

IF OBJECT_ID('MOD_FORM_USAGE', 'U') IS NOT NULL DROP TABLE MOD_FORM_USAGE;
IF OBJECT_ID('MOD_DIAGRAM_USAGE', 'U') IS NOT NULL DROP TABLE MOD_DIAGRAM_USAGE;
IF OBJECT_ID('MOD_FORMS', 'U') IS NOT NULL DROP TABLE MOD_FORMS;
IF OBJECT_ID('MOD_USER_SESSIONS', 'U') IS NOT NULL DROP TABLE MOD_USER_SESSIONS;
IF OBJECT_ID('MOD_PROCESSES_DIAGRAMS_AUD', 'U') IS NOT NULL DROP TABLE MOD_PROCESSES_DIAGRAMS_AUD;
IF OBJECT_ID('MOD_REVINFO', 'U') IS NOT NULL DROP TABLE MOD_REVINFO;
IF OBJECT_ID('MOD_PROCESSES_DIAGRAMS', 'U') IS NOT NULL DROP TABLE MOD_PROCESSES_DIAGRAMS;
IF OBJECT_ID('MOD_ELEMENT_TEMPLATES', 'U') IS NOT NULL DROP TABLE MOD_ELEMENT_TEMPLATES;
