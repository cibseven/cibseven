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

-- Notifications: messages administrators publish to logged-in webclient users (CIB7-1867)
CREATE TABLE NOTIFICATIONS (
    ID           VARCHAR(36)   NOT NULL,
    TITLE        VARCHAR(255)  NOT NULL,
    BODY         CLOB NOT NULL,
    -- INFO | WARNING | CRITICAL
    SEVERITY     VARCHAR(20)   NOT NULL,
    -- ALL | GROUPS (for GROUPS, the groups are in NOTIFICATION_GROUPS)
    AUDIENCE     VARCHAR(20)   NOT NULL,
    VALID_FROM   TIMESTAMP NOT NULL,
    VALID_TO     TIMESTAMP,
    DISMISSIBLE  SMALLINT DEFAULT 1 NOT NULL,
    LINK         VARCHAR(1000),
    ACTIVE       SMALLINT DEFAULT 0 NOT NULL,
    CREATED_AT   TIMESTAMP NOT NULL,
    CREATED_BY   VARCHAR(255)  NOT NULL,
    UPDATED_AT   TIMESTAMP,
    UPDATED_BY   VARCHAR(255),
    CONSTRAINT NOTIF_PK_NOTIFICATIONS PRIMARY KEY (ID)
);

CREATE INDEX NOTIF_IDX_NOTIFICATIONS_ACTIVE ON NOTIFICATIONS (ACTIVE, VALID_FROM, VALID_TO);

CREATE TABLE NOTIFICATION_GROUPS (
    NOTIFICATION_ID VARCHAR(36)  NOT NULL,
    GROUP_ID        VARCHAR(255) NOT NULL,
    CONSTRAINT NOTIF_PK_GROUPS PRIMARY KEY (NOTIFICATION_ID, GROUP_ID),
    CONSTRAINT NOTIF_FK_GROUPS_NOTIFICATION FOREIGN KEY (NOTIFICATION_ID) REFERENCES NOTIFICATIONS (ID) ON DELETE CASCADE
);

CREATE INDEX NOTIF_IDX_GROUPS_GROUP ON NOTIFICATION_GROUPS (GROUP_ID);
