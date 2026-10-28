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

-- ACT_GE_PROPERTY belongs to the engine component, which is always dropped after this one.
DELETE FROM ACT_GE_PROPERTY WHERE NAME_ = 'webapp.schema.version';

DROP INDEX NOTIF_IDX_GROUPS_GROUP ON NOTIF_GROUPS;
DROP TABLE NOTIF_GROUPS;
DROP INDEX NOTIF_IDX_NOTIFICATIONS_ACTIVE ON NOTIF_NOTIFICATIONS;
DROP TABLE NOTIF_NOTIFICATIONS;

DROP INDEX CHAT_IDX_MESSAGES_ROOM_EL_CREATED ON CHAT_MESSAGES;
DROP INDEX CHAT_IDX_MESSAGES_ROOM_CREATED ON CHAT_MESSAGES;
DROP TABLE CHAT_MESSAGES;
DROP TABLE CHAT_PRESENCE;

DROP TABLE MOD_FORM_USAGE;
DROP TABLE MOD_DIAGRAM_USAGE;
DROP TABLE MOD_FORMS_AUD;
DROP TABLE MOD_FORMS;
DROP TABLE MOD_USER_SESSIONS;
DROP TABLE MOD_PROCESSES_DIAGRAMS_AUD;
DROP TABLE MOD_REVINFO;
DROP TABLE MOD_PROCESSES_DIAGRAMS;
DROP INDEX MOD_IDX_PROJECT_MEMBERS_MEMBER ON MOD_PROJECT_MEMBERS;
DROP TABLE MOD_PROJECT_MEMBERS;
DROP INDEX MOD_IDX_FOLDERS_PROJECT ON MOD_FOLDERS;
DROP INDEX MOD_IDX_FOLDERS_PARENT ON MOD_FOLDERS;
DROP TABLE MOD_FOLDERS;
DROP TABLE MOD_ELEMENT_TEMPLATES;
