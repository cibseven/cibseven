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
CREATE TABLE CHAT_MESSAGES (
    ID           NVARCHAR(36)  NOT NULL,
    ROOM_ID      NVARCHAR(255) NOT NULL,
    ELEMENT_ID   NVARCHAR(255),
    USER_ID      NVARCHAR(255) NOT NULL,
    DISPLAY_NAME NVARCHAR(255),
    CONTENT      NVARCHAR(MAX) NOT NULL,
    CREATED_AT   DATETIME2     NOT NULL,
    EDITED_AT    DATETIME2,
    DELETED_AT   DATETIME2,
    CONSTRAINT CHAT_PK_MESSAGES PRIMARY KEY (ID)
);

CREATE INDEX CHAT_IDX_MESSAGES_ROOM_CREATED    ON CHAT_MESSAGES (ROOM_ID, CREATED_AT);
CREATE INDEX CHAT_IDX_MESSAGES_ROOM_EL_CREATED ON CHAT_MESSAGES (ROOM_ID, ELEMENT_ID, CREATED_AT);

CREATE TABLE CHAT_PRESENCE (
    ROOM_ID      NVARCHAR(255) NOT NULL,
    USER_ID      NVARCHAR(255) NOT NULL,
    DISPLAY_NAME NVARCHAR(255),
    LAST_SEEN    DATETIME2     NOT NULL,
    CONSTRAINT CHAT_PK_PRESENCE PRIMARY KEY (ROOM_ID, USER_ID)
);
