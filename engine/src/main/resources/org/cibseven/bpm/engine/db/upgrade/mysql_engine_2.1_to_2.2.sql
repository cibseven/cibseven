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
    active TINYINT(1) DEFAULT 1 NOT NULL,
    version INT(11) DEFAULT 1,
    template_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description LONGTEXT,
    origin VARCHAR(50) NOT NULL,
    content LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_processes_diagrams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    processkey VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(150),
    created TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    active TINYINT(1) DEFAULT 1 NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'bpmn-c7',
    version INT(11),
    diagram LONGBLOB,
    updated_by VARCHAR(100) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_revinfo (
    rev INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revtstmp BIGINT(20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_processes_diagrams_aud (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(255),
    processkey VARCHAR(100),
    description VARCHAR(150),
    created TIMESTAMP NULL DEFAULT NULL,
    updated TIMESTAMP NULL DEFAULT NULL,
    active TINYINT(1) DEFAULT 1,
    type VARCHAR(50) DEFAULT 'bpmn-c7',
    version INT(11),
    diagram_mod TINYINT(1) DEFAULT 0,
    diagram LONGBLOB,
    updated_by VARCHAR(100),
    rev INT(11) NOT NULL,
    revtype SMALLINT(6),
    CONSTRAINT mod_pk_resources_aud PRIMARY KEY (id, rev),
    CONSTRAINT mod_fk_resources_aud_rev FOREIGN KEY (rev) REFERENCES mod_revinfo(rev)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_user_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_diagram_usage (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    diagram_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_diagram_usage_diagram FOREIGN KEY (diagram_id) REFERENCES mod_processes_diagrams(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_diagram_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_forms (
    id VARCHAR(36) PRIMARY KEY,
    description VARCHAR(150),
    created TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated TIMESTAMP DEFAULT '0000-00-00 00:00:00',
    active TINYINT(1) DEFAULT 1 NOT NULL,
    form_schema LONGBLOB NOT NULL,
    formid VARCHAR(100) NOT NULL UNIQUE,
    version INT(11) DEFAULT 1,
    updated_by VARCHAR(100) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mod_form_usage (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    form_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT mod_fk_form_usage_form FOREIGN KEY (form_id) REFERENCES mod_forms(id) ON DELETE CASCADE,
    CONSTRAINT mod_fk_form_usage_session FOREIGN KEY (session_id) REFERENCES mod_user_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;