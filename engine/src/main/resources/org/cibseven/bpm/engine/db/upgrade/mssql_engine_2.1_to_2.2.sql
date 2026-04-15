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
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    active BIT DEFAULT 1 NOT NULL,
    version INT DEFAULT 1,
    template_id NVARCHAR(100) NOT NULL UNIQUE,
    name NVARCHAR(200) NOT NULL,
    description NVARCHAR(MAX),
    origin NVARCHAR(50) NOT NULL,
    content NVARCHAR(MAX),
    created_at DATETIME2 DEFAULT GETDATE() NOT NULL,
    updated_at DATETIME2 DEFAULT GETDATE() NOT NULL,
    created_by NVARCHAR(100),
    updated_by NVARCHAR(100)
);

CREATE TABLE mod_processes_diagrams (
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    name NVARCHAR(255) NOT NULL,
    processkey NVARCHAR(100) NOT NULL UNIQUE,
    description NVARCHAR(150),
    created DATETIME2 DEFAULT GETDATE() NOT NULL,
    updated DATETIME2 DEFAULT GETDATE() NOT NULL,
    active BIT DEFAULT 1 NOT NULL,
    type NVARCHAR(50) NOT NULL DEFAULT 'bpmn-c7',
    version INT,
    diagram VARBINARY(MAX),
    updated_by NVARCHAR(100)
);

CREATE TABLE mod_revinfo (
    rev INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    revtstmp BIGINT
);

CREATE TABLE mod_processes_diagrams_aud (
    id NVARCHAR(36) NOT NULL,
    name NVARCHAR(255),
    processkey NVARCHAR(100),
    description NVARCHAR(150),
    created DATETIME2,
    updated DATETIME2,
    active BIT DEFAULT 1,
    type NVARCHAR(50) DEFAULT 'bpmn-c7',
    version INT,
    diagram_mod BIT DEFAULT 0,
    diagram VARBINARY(MAX),
    updated_by NVARCHAR(100),
    rev INT NOT NULL,
    revtype SMALLINT,
    CONSTRAINT mod_pk_resources_aud PRIMARY KEY (id, rev),
    CONSTRAINT mod_fk_resources_aud_rev FOREIGN KEY (rev) REFERENCES mod_revinfo(rev)
);

CREATE SEQUENCE mod_hibernate_sequence
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    CACHE 1;

CREATE TABLE mod_user_sessions (
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    user_id NVARCHAR(100) NOT NULL,
    created_at DATETIME2 DEFAULT GETDATE() NOT NULL,
    expires_at DATETIME2
);

CREATE TABLE mod_diagram_usage (
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    user_id NVARCHAR(100) NOT NULL,
    diagram_id NVARCHAR(36) NOT NULL,
    session_id NVARCHAR(36) NOT NULL,
    opened_at DATETIME2 DEFAULT GETDATE() NOT NULL,
    closed_at DATETIME2,
    CONSTRAINT mod_fk_diagram_usage_diagram FOREIGN KEY (diagram_id) REFERENCES mod_processes_diagrams(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_diagram_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id)
);

CREATE TABLE mod_forms (
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    description NVARCHAR(150),
    created DATETIME2 DEFAULT GETDATE(),
    updated DATETIME2 DEFAULT '1970-01-01T00:00:00',
    active BIT DEFAULT 1 NOT NULL,
    form_schema VARBINARY(MAX) NOT NULL,
    formid NVARCHAR(100) NOT NULL UNIQUE,
    version INT DEFAULT 1,
    updated_by NVARCHAR(100)
);

CREATE TABLE mod_form_usage (
    id NVARCHAR(36) NOT NULL PRIMARY KEY,
    user_id NVARCHAR(100) NOT NULL,
    form_id NVARCHAR(36) NOT NULL,
    session_id NVARCHAR(36) NOT NULL,
    opened_at DATETIME2 DEFAULT GETDATE() NOT NULL,
    closed_at DATETIME2,
    CONSTRAINT mod_fk_form_usage_form FOREIGN KEY (form_id) REFERENCES mod_forms(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_form_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id)
);
