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

insert into ACT_GE_SCHEMA_LOG
values ('1600', CURRENT_TIMESTAMP, '2.3.0');

-- Chat: soft-delete tombstone (long-polling change detection)
ALTER TABLE CHAT_MESSAGES ADD COLUMN DELETED_AT DATETIME(6) NULL;

-- Chat: DB-backed presence for long-polling transport
CREATE TABLE CHAT_PRESENCE (
    ROOM_ID      VARCHAR(255) NOT NULL,
    USER_ID      VARCHAR(255) NOT NULL,
    DISPLAY_NAME VARCHAR(255),
    LAST_SEEN    DATETIME(6)  NOT NULL,
    CONSTRAINT CHAT_PK_PRESENCE PRIMARY KEY (ROOM_ID, USER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Modeler: one snapshot per form save, so a form can be restored to an earlier state
CREATE TABLE IF NOT EXISTS MOD_FORMS_AUD (
    ID VARCHAR(36) NOT NULL,
    DESCRIPTION VARCHAR(150),
    CREATED TIMESTAMP NULL DEFAULT NULL,
    UPDATED TIMESTAMP NULL DEFAULT NULL,
    ACTIVE TINYINT(1) DEFAULT 1,
    FORM_SCHEMA LONGBLOB,
    FORMID VARCHAR(100),
    VERSION INT(11) DEFAULT 1,
    SCHEMA_MOD TINYINT(1) DEFAULT 0,
    UPDATED_BY VARCHAR(100),
    REV BIGINT NOT NULL,
    REVTYPE SMALLINT,
    CONSTRAINT MOD_PK_FORMS_AUD PRIMARY KEY (ID, REV),
    CONSTRAINT MOD_FK_FORMS_AUD_REV FOREIGN KEY (REV) REFERENCES MOD_REVINFO(REV)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
