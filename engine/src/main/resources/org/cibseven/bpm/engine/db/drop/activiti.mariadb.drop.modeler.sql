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

ALTER TABLE CHAT_MESSAGES DROP INDEX CHAT_IDX_MESSAGES_ROOM_EL_CREATED;
ALTER TABLE CHAT_MESSAGES DROP INDEX CHAT_IDX_MESSAGES_ROOM_CREATED;
DROP TABLE IF EXISTS CHAT_MESSAGES;

DROP TABLE IF EXISTS MOD_FORM_USAGE;
DROP TABLE IF EXISTS MOD_DIAGRAM_USAGE;
DROP TABLE IF EXISTS MOD_FORMS;
DROP TABLE IF EXISTS MOD_USER_SESSIONS;
DROP TABLE IF EXISTS MOD_PROCESSES_DIAGRAMS_AUD;
DROP TABLE IF EXISTS MOD_REVINFO;
DROP TABLE IF EXISTS MOD_PROCESSES_DIAGRAMS;
DROP TABLE IF EXISTS MOD_ELEMENT_TEMPLATES;
