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

DROP INDEX CHAT_IDX_MESSAGES_ROOM_EL_CREATED;
DROP INDEX CHAT_IDX_MESSAGES_ROOM_CREATED;
DROP TABLE CHAT_MESSAGES;

DROP TABLE MOD_FORM_USAGE CASCADE CONSTRAINTS;
DROP TABLE MOD_DIAGRAM_USAGE CASCADE CONSTRAINTS;
DROP TABLE MOD_FORMS CASCADE CONSTRAINTS;
DROP TABLE MOD_USER_SESSIONS CASCADE CONSTRAINTS;
DROP TABLE MOD_PROCESSES_DIAGRAMS_AUD CASCADE CONSTRAINTS;
DROP TABLE MOD_REVINFO CASCADE CONSTRAINTS;
DROP TABLE MOD_PROCESSES_DIAGRAMS CASCADE CONSTRAINTS;
DROP TABLE MOD_ELEMENT_TEMPLATES CASCADE CONSTRAINTS;
