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
values ('1500', CURRENT_TIMESTAMP, '2.2.0');



-- MODELER

CREATE TABLE mod_element_templates (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    active NUMBER(1) DEFAULT 1,
    version NUMBER(11) DEFAULT 1,
    template_id VARCHAR2(100 CHAR) NOT NULL UNIQUE,
    name VARCHAR2(200 CHAR) NOT NULL,
    description CLOB,
    origin VARCHAR2(50 CHAR) NOT NULL,
    content CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by VARCHAR2(100 CHAR),
    updated_by VARCHAR2(100 CHAR)
);

CREATE TABLE mod_processes_diagrams (
    id VARCHAR2(36) PRIMARY KEY,
    name VARCHAR2(255),
    processkey VARCHAR2(100) NOT NULL UNIQUE,
    description VARCHAR2(150),
    created TIMESTAMP,
    updated TIMESTAMP,
    active NUMBER(1) DEFAULT 1 NOT NULL,
    type VARCHAR2(50) NOT NULL,
    version NUMBER(10,0) DEFAULT 1,
    diagram BLOB,
    updated_by VARCHAR2(100 CHAR)
);

CREATE TABLE mod_revinfo (
    rev NUMBER(19) NOT NULL PRIMARY KEY,
    revtstmp NUMBER(19)
);

CREATE SEQUENCE mod_revinfo_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE mod_processes_diagrams_aud (
    id VARCHAR2(36) NOT NULL,
    name VARCHAR2(255),
    processkey VARCHAR2(100),
    description VARCHAR2(150),
    created TIMESTAMP,
    updated TIMESTAMP,
    active NUMBER(1) DEFAULT 1,
    type VARCHAR2(50),
    version NUMBER(11) DEFAULT 1,
    diagram_mod NUMBER(1) DEFAULT 0,
    diagram BLOB,
    updated_by VARCHAR2(100 CHAR),
    rev NUMBER(19) NOT NULL,
    revtype NUMBER(6,0),
    CONSTRAINT mod_pk_resources_aud PRIMARY KEY (id, rev),
    CONSTRAINT mod_fk_resources_aud_rev FOREIGN KEY (rev) REFERENCES mod_revinfo(rev)
);

CREATE TABLE mod_user_sessions (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(100 CHAR) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP
);

CREATE TABLE mod_diagram_usage (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(100 CHAR) NOT NULL,
    diagram_id VARCHAR2(36) NOT NULL,
    session_id VARCHAR2(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_diagram_usage_diagram FOREIGN KEY (diagram_id) REFERENCES mod_processes_diagrams(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_diagram_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
);

CREATE TABLE mod_forms (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    description VARCHAR2(150 CHAR),
    created TIMESTAMP,
    updated TIMESTAMP,
    active NUMBER(1) DEFAULT 1 NOT NULL,
    form_schema BLOB NOT NULL,
    formid VARCHAR2(100 CHAR) NOT NULL UNIQUE,
    version NUMBER(11) DEFAULT 1,
    updated_by VARCHAR2(100 CHAR)
);

CREATE TABLE mod_form_usage (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(100 CHAR) NOT NULL,
    form_id VARCHAR2(36) NOT NULL,
    session_id VARCHAR2(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_form_usage_form FOREIGN KEY (form_id) REFERENCES mod_forms(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_form_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
);
