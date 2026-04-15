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

CREATE TABLE IF NOT EXISTS mod_element_templates (
    id VARCHAR(36) PRIMARY KEY,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    version INTEGER DEFAULT 1,
    template_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    origin VARCHAR(50) NOT NULL,
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS mod_processes_diagrams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    processkey VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(150),
    created TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'bpmn-c7',
    version INTEGER,
    diagram BYTEA,
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS mod_revinfo (
    rev SERIAL PRIMARY KEY,
    revtstmp BIGINT
);

CREATE TABLE IF NOT EXISTS mod_processes_diagrams_aud (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(255),
    processkey VARCHAR(100),
    description VARCHAR(150),
    created TIMESTAMP,
    updated TIMESTAMP,
    active BOOLEAN DEFAULT TRUE,
    type VARCHAR(50) DEFAULT 'bpmn-c7',
    version INTEGER,
    diagram_mod BOOLEAN DEFAULT false,
    diagram BYTEA,
    updated_by VARCHAR(100),
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    CONSTRAINT mod_pk_resources_aud PRIMARY KEY (id, rev),
    CONSTRAINT mod_fk_resources_aud_rev FOREIGN KEY (rev) REFERENCES mod_revinfo(rev)
);

CREATE SEQUENCE IF NOT EXISTS mod_hibernate_sequence
    START 1
    INCREMENT 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    CACHE 1;

CREATE TABLE IF NOT EXISTS mod_user_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mod_diagram_usage (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    diagram_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_diagram_usage_diagram FOREIGN KEY (diagram_id) REFERENCES mod_processes_diagrams(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_diagram_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS mod_forms (
    id VARCHAR(36) PRIMARY KEY,
    description VARCHAR(150),
    created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated TIMESTAMP DEFAULT '1970-01-01 00:00:00'::timestamp,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    form_schema BYTEA NOT NULL,
    formid VARCHAR(100) NOT NULL UNIQUE,
    version INTEGER DEFAULT 1,
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS mod_form_usage (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    form_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_form_usage_form FOREIGN KEY (form_id) REFERENCES mod_forms(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_form_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
);
