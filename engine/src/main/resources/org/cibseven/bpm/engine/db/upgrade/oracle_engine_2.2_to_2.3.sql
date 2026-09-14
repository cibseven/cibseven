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
ALTER TABLE CHAT_MESSAGES ADD (DELETED_AT TIMESTAMP);

-- Chat: DB-backed presence for long-polling transport
CREATE TABLE CHAT_PRESENCE (
    ROOM_ID      VARCHAR2(255) NOT NULL,
    USER_ID      VARCHAR2(255) NOT NULL,
    DISPLAY_NAME VARCHAR2(255),
    LAST_SEEN    TIMESTAMP     NOT NULL,
    CONSTRAINT CHAT_PK_PRESENCE PRIMARY KEY (ROOM_ID, USER_ID)
);


-- Modeler: one snapshot per form save, so a form can be restored to an earlier state
CREATE TABLE MOD_FORMS_AUD (
    ID VARCHAR2(36) NOT NULL,
    DESCRIPTION VARCHAR2(150),
    CREATED TIMESTAMP,
    UPDATED TIMESTAMP,
    ACTIVE NUMBER(1) DEFAULT 1,
    FORM_SCHEMA BLOB,
    FORMID VARCHAR2(100),
    VERSION NUMBER(11) DEFAULT 1,
    SCHEMA_MOD NUMBER(1) DEFAULT 0,
    UPDATED_BY VARCHAR2(100 CHAR),
    REV NUMBER(19) NOT NULL,
    REVTYPE NUMBER(6,0),
    CONSTRAINT MOD_PK_FORMS_AUD PRIMARY KEY (ID, REV),
    CONSTRAINT MOD_FK_FORMS_AUD_REV FOREIGN KEY (REV) REFERENCES MOD_REVINFO(REV)
);


-- Modeler folders. Creates the folder table, gives diagrams and forms the folder they belong to,
-- and puts the models of an installation that has none yet into a folder named General, so an
-- upgrade leaves nothing to sort by hand.
-- The folder id is written out rather than generated: the update below has to name the same
-- id the insert used, and generating one is spelled differently on every database.
CREATE TABLE MOD_FOLDERS (
    ID VARCHAR2(36 CHAR) NOT NULL PRIMARY KEY,
    PARENT_ID VARCHAR2(36 CHAR),
    SOURCE VARCHAR2(50 CHAR) NOT NULL,
    NAME VARCHAR2(255 CHAR) NOT NULL,
    CREATED TIMESTAMP,
    CREATED_BY VARCHAR2(100 CHAR),
    UPDATED TIMESTAMP,
    UPDATED_BY VARCHAR2(100 CHAR),
    CONSTRAINT MOD_UK_FOLDERS_PARENT_NAME UNIQUE (SOURCE, PARENT_ID, NAME),
    CONSTRAINT MOD_FK_FOLDERS_PARENT FOREIGN KEY (PARENT_ID) REFERENCES MOD_FOLDERS(ID)
);

CREATE INDEX MOD_IDX_FOLDERS_PARENT ON MOD_FOLDERS (PARENT_ID);

INSERT INTO MOD_FOLDERS (ID, PARENT_ID, SOURCE, NAME, CREATED)
    VALUES ('00000000-0000-0000-0000-0000000000d2', NULL, 'DATABASE', 'General', SYSTIMESTAMP);

ALTER TABLE MOD_PROCESSES_DIAGRAMS ADD FOLDER_ID VARCHAR2(36 CHAR);
ALTER TABLE MOD_FORMS ADD FOLDER_ID VARCHAR2(36 CHAR);

UPDATE MOD_PROCESSES_DIAGRAMS SET FOLDER_ID = '00000000-0000-0000-0000-0000000000d2' WHERE FOLDER_ID IS NULL;
UPDATE MOD_FORMS SET FOLDER_ID = '00000000-0000-0000-0000-0000000000d2' WHERE FOLDER_ID IS NULL;

ALTER TABLE MOD_PROCESSES_DIAGRAMS ADD CONSTRAINT MOD_FK_DIAGRAMS_FOLDER FOREIGN KEY (FOLDER_ID) REFERENCES MOD_FOLDERS(ID);
ALTER TABLE MOD_FORMS ADD CONSTRAINT MOD_FK_FORMS_FOLDER FOREIGN KEY (FOLDER_ID) REFERENCES MOD_FOLDERS(ID);
