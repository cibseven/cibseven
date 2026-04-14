--
-- Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
-- under one or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information regarding copyright
-- ownership. Camunda licenses this file to you under the Apache License,
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

-- CIB seven 2.2: baseline schema for cibseven-webclient (MOD_ namespace).

create table MOD_ELEMENT_TEMPLATES (
    id varchar(36) not null,
    active boolean default true not null,
    version integer default 1,
    template_id varchar(100) not null,
    name varchar(200) not null,
    description clob,
    origin varchar(50) not null,
    content clob,
    created_at timestamp default current_timestamp not null,
    updated_at timestamp default current_timestamp not null,
    created_by varchar(100),
    updated_by varchar(100),
    constraint pk_mod_element_templates primary key (id),
    constraint uq_mod_element_templates_template_id unique (template_id)
);

create table MOD_PROCESSES_DIAGRAMS (
    id varchar(36) not null,
    name varchar(255) not null,
    processkey varchar(100) not null,
    description varchar(150),
    created timestamp default current_timestamp not null,
    updated timestamp default current_timestamp not null,
    active boolean default true not null,
    type varchar(50) default 'bpmn-c7' not null,
    version integer,
    diagram blob,
    updated_by varchar(100),
    constraint pk_mod_processes_diagrams primary key (id),
    constraint uq_mod_processes_diagrams_processkey unique (processkey)
);

create table MOD_REVINFO (
    REV int auto_increment not null,
    REVTSTMP bigint,
    constraint pk_mod_revinfo primary key (REV)
);

create table MOD_PROCESSES_DIAGRAMS_AUD (
    id varchar(36) not null,
    name varchar(255),
    processkey varchar(100),
    description varchar(150),
    created timestamp,
    updated timestamp,
    active boolean default true,
    type varchar(50) default 'bpmn-c7',
    version integer,
    diagram_mod boolean default false,
    diagram blob,
    updated_by varchar(100),
    rev int not null,
    revtype tinyint,
    constraint pk_mod_processes_diagrams_aud primary key (id, rev),
    constraint fk_mod_processes_diagrams_aud_rev foreign key (rev) references MOD_REVINFO(REV)
);

create sequence revinfo_seq start with 1 increment by 50;

create sequence hibernate_sequence start with 1 increment by 1;

create table MOD_USER_SESSIONS (
    id varchar(36) not null,
    user_id varchar(100) not null,
    created_at timestamp default current_timestamp not null,
    expires_at timestamp,
    constraint pk_mod_user_sessions primary key (id)
);

create table MOD_DIAGRAM_USAGE (
    id varchar(36) not null,
    user_id varchar(100) not null,
    diagram_id varchar(36) not null,
    session_id varchar(36) not null,
    opened_at timestamp default current_timestamp not null,
    closed_at timestamp,
    constraint pk_mod_diagram_usage primary key (id),
    constraint fk_mod_diagram_usage_diagram foreign key (diagram_id) references MOD_PROCESSES_DIAGRAMS(id) on delete cascade,
    constraint fk_mod_diagram_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

create table MOD_FORMS (
    id varchar(36) not null,
    description varchar(150),
    created timestamp default current_timestamp,
    updated timestamp default '1970-01-01 00:00:00',
    active boolean default true not null,
    form_schema blob not null,
    formid varchar(100) not null,
    version integer default 1,
    updated_by varchar(100),
    constraint pk_mod_forms primary key (id),
    constraint uq_mod_forms_formid unique (formid)
);

create table MOD_FORM_USAGE (
    id varchar(36) not null,
    user_id varchar(100) not null,
    form_id varchar(36) not null,
    session_id varchar(36) not null,
    opened_at timestamp default current_timestamp not null,
    closed_at timestamp,
    constraint pk_mod_form_usage primary key (id),
    constraint fk_mod_form_usage_form foreign key (form_id) references MOD_FORMS(id) on delete cascade,
    constraint fk_mod_form_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

insert into ACT_GE_SCHEMA_LOG
values ('1400', CURRENT_TIMESTAMP, 'cibseven-2.2.0');
