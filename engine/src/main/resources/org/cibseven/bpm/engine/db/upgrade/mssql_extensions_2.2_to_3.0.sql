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

-- Webclient tables (MOD_, CHAT_, NOTIF_) - the extensions schema component. They are not part
-- of the engine schema, so an installation without the CIB seven webclient can skip this script.

-- Chat: soft-delete tombstone (long-polling change detection)
ALTER TABLE CHAT_MESSAGES ADD DELETED_AT DATETIME2;

-- Chat: DB-backed presence for long-polling transport
CREATE TABLE CHAT_PRESENCE (
    ROOM_ID      NVARCHAR(255) NOT NULL,
    USER_ID      NVARCHAR(255) NOT NULL,
    DISPLAY_NAME NVARCHAR(255),
    LAST_SEEN    DATETIME2     NOT NULL,
    CONSTRAINT CHAT_PK_PRESENCE PRIMARY KEY (ROOM_ID, USER_ID)
);


-- Modeler: one snapshot per form save, so a form can be restored to an earlier state
CREATE TABLE MOD_FORMS_AUD (
    ID NVARCHAR(36) NOT NULL,
    DESCRIPTION NVARCHAR(150),
    CREATED DATETIME2,
    UPDATED DATETIME2,
    ACTIVE BIT DEFAULT 1,
    FORM_SCHEMA VARBINARY(MAX),
    FORMID NVARCHAR(100),
    VERSION INT DEFAULT 1,
    SCHEMA_MOD BIT DEFAULT 0,
    UPDATED_BY NVARCHAR(100),
    REV BIGINT NOT NULL,
    REVTYPE SMALLINT,
    CONSTRAINT MOD_PK_FORMS_AUD PRIMARY KEY (ID, REV),
    CONSTRAINT MOD_FK_FORMS_AUD_REV FOREIGN KEY (REV) REFERENCES MOD_REVINFO(REV)
);


-- Modeler folders. Models of an installation that has none yet move into General, whose id is
-- written out because the update has to name it and generating one differs on every database.
CREATE TABLE MOD_FOLDERS (
    ID NVARCHAR(36) NOT NULL PRIMARY KEY,
    PARENT_ID NVARCHAR(36),
    PROJECT_ID NVARCHAR(36),
    NAME NVARCHAR(255) NOT NULL,
    CREATED DATETIME2,
    CREATED_BY NVARCHAR(100),
    UPDATED DATETIME2,
    UPDATED_BY NVARCHAR(100),
    CONSTRAINT MOD_UK_FOLDERS_PARENT_NAME UNIQUE (PARENT_ID, NAME),
    CONSTRAINT MOD_FK_FOLDERS_PARENT FOREIGN KEY (PARENT_ID) REFERENCES MOD_FOLDERS(ID)
);

CREATE INDEX MOD_IDX_FOLDERS_PARENT ON MOD_FOLDERS (PARENT_ID);
CREATE INDEX MOD_IDX_FOLDERS_PROJECT ON MOD_FOLDERS (PROJECT_ID);

INSERT INTO MOD_FOLDERS (ID, PARENT_ID, PROJECT_ID, NAME, CREATED)
    VALUES ('00000000-0000-0000-0000-0000000000d1', NULL, '00000000-0000-0000-0000-0000000000d1', 'General', SYSDATETIME());

ALTER TABLE MOD_PROCESSES_DIAGRAMS ADD FOLDER_ID NVARCHAR(36);
ALTER TABLE MOD_FORMS ADD FOLDER_ID NVARCHAR(36);

UPDATE MOD_PROCESSES_DIAGRAMS SET FOLDER_ID = '00000000-0000-0000-0000-0000000000d1' WHERE FOLDER_ID IS NULL;
UPDATE MOD_FORMS SET FOLDER_ID = '00000000-0000-0000-0000-0000000000d1' WHERE FOLDER_ID IS NULL;

ALTER TABLE MOD_PROCESSES_DIAGRAMS ADD CONSTRAINT MOD_FK_DIAGRAMS_FOLDER FOREIGN KEY (FOLDER_ID) REFERENCES MOD_FOLDERS(ID);
ALTER TABLE MOD_FORMS ADD CONSTRAINT MOD_FK_FORMS_FOLDER FOREIGN KEY (FOLDER_ID) REFERENCES MOD_FOLDERS(ID);

-- Modeler (CIB7-2277): a process key and a form id are unique within their folder only. 2.2
-- declared them unique inline, so the database chose the names of those constraints.
-- Each lookup is a single statement, since the scripts are split at semicolons.
DECLARE @processKeyConstraint NVARCHAR(258)
SELECT @processKeyConstraint = QUOTENAME(kc.name) FROM sys.key_constraints kc
    JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
    WHERE kc.type = 'UQ' AND kc.parent_object_id = OBJECT_ID('MOD_PROCESSES_DIAGRAMS') AND c.name = 'PROCESSKEY'
EXEC ('ALTER TABLE MOD_PROCESSES_DIAGRAMS DROP CONSTRAINT ' + @processKeyConstraint);
DECLARE @formIdConstraint NVARCHAR(258)
SELECT @formIdConstraint = QUOTENAME(kc.name) FROM sys.key_constraints kc
    JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
    WHERE kc.type = 'UQ' AND kc.parent_object_id = OBJECT_ID('MOD_FORMS') AND c.name = 'FORMID'
EXEC ('ALTER TABLE MOD_FORMS DROP CONSTRAINT ' + @formIdConstraint);
ALTER TABLE MOD_PROCESSES_DIAGRAMS ADD CONSTRAINT MOD_UK_DIAGRAMS_KEY_FOLDER UNIQUE (PROCESSKEY, FOLDER_ID);
ALTER TABLE MOD_FORMS ADD CONSTRAINT MOD_UK_FORMS_FORMID_FOLDER UNIQUE (FORMID, FOLDER_ID);

-- Modeler project members (CIB7-1288): who may work on a project, i.e. a top-level folder.
-- No rows are written here: a project without members stays open to every modeler user.
CREATE TABLE MOD_PROJECT_MEMBERS (
    ID NVARCHAR(36) NOT NULL PRIMARY KEY,
    PROJECT_ID NVARCHAR(36) NOT NULL,
    -- USER | GROUP
    MEMBER_TYPE NVARCHAR(10) NOT NULL,
    MEMBER_ID NVARCHAR(255) NOT NULL,
    -- PROJECT_ADMIN | EDITOR | VIEWER
    MEMBER_ROLE NVARCHAR(30) NOT NULL,
    CREATED DATETIME2,
    CREATED_BY NVARCHAR(100),
    UPDATED DATETIME2,
    UPDATED_BY NVARCHAR(100),
    CONSTRAINT MOD_UK_PROJECT_MEMBERS UNIQUE (PROJECT_ID, MEMBER_TYPE, MEMBER_ID),
    CONSTRAINT MOD_FK_PROJECT_MEMBERS_PROJECT FOREIGN KEY (PROJECT_ID) REFERENCES MOD_FOLDERS(ID) ON DELETE CASCADE
);

CREATE INDEX MOD_IDX_PROJECT_MEMBERS_MEMBER ON MOD_PROJECT_MEMBERS (MEMBER_TYPE, MEMBER_ID);

-- Notifications: messages administrators publish to logged-in webclient users (CIB7-1867)
CREATE TABLE NOTIF_NOTIFICATIONS (
    ID           NVARCHAR(36)   NOT NULL,
    TITLE        NVARCHAR(255)  NOT NULL,
    BODY         NVARCHAR(MAX) NOT NULL,
    -- INFO | WARNING | CRITICAL
    SEVERITY     NVARCHAR(20)   NOT NULL,
    -- ALL | GROUPS (for GROUPS, the groups are in NOTIF_GROUPS)
    AUDIENCE     NVARCHAR(20)   NOT NULL,
    VALID_FROM   DATETIME2 NOT NULL,
    VALID_TO     DATETIME2,
    DISMISSIBLE  BIT DEFAULT 1 NOT NULL,
    LINK         NVARCHAR(1000),
    ACTIVE       BIT DEFAULT 0 NOT NULL,
    CREATED_AT   DATETIME2 NOT NULL,
    CREATED_BY   NVARCHAR(255)  NOT NULL,
    UPDATED_AT   DATETIME2,
    UPDATED_BY   NVARCHAR(255),
    CONSTRAINT NOTIF_PK_NOTIFICATIONS PRIMARY KEY (ID)
);

CREATE INDEX NOTIF_IDX_NOTIFICATIONS_ACTIVE ON NOTIF_NOTIFICATIONS (ACTIVE, VALID_FROM, VALID_TO);

CREATE TABLE NOTIF_GROUPS (
    NOTIFICATION_ID NVARCHAR(36)  NOT NULL,
    GROUP_ID        NVARCHAR(255) NOT NULL,
    CONSTRAINT NOTIF_PK_GROUPS PRIMARY KEY (NOTIFICATION_ID, GROUP_ID),
    CONSTRAINT NOTIF_FK_GROUPS_NOTIFICATION FOREIGN KEY (NOTIFICATION_ID) REFERENCES NOTIF_NOTIFICATIONS (ID) ON DELETE CASCADE
);

CREATE INDEX NOTIF_IDX_GROUPS_GROUP ON NOTIF_GROUPS (GROUP_ID);

-- Version of the webclient schema, see the create.extensions script. Deleted before inserting because
-- a 2.2 schema has no such row yet, and this form needs no dialect-specific upsert.
DELETE FROM ACT_GE_PROPERTY WHERE NAME_ = 'webclient.extensions.version';
INSERT INTO ACT_GE_PROPERTY VALUES ('webclient.extensions.version', '3.0.0', 1);