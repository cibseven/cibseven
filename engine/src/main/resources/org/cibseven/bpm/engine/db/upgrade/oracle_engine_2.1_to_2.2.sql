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
    id varchar2(36 char) not null,
    active number(1) default 1 not null,
    version number(11) default 1,
    template_id varchar2(100 char) not null,
    name varchar2(200 char) not null,
    description clob,
    origin varchar2(50 char) not null,
    content clob,
    created_at timestamp default current_timestamp not null,
    updated_at timestamp default current_timestamp not null,
    created_by varchar2(100 char),
    updated_by varchar2(100 char),
    constraint pk_mod_element_templates primary key (id),
    constraint uq_mod_element_templates_tmpl unique (template_id)
);

create table MOD_PROCESSES_DIAGRAMS (
    id varchar2(36) not null,
    name varchar2(255) not null,
    processkey varchar2(100) not null,
    description varchar2(150),
    created timestamp default current_timestamp not null,
    updated timestamp default current_timestamp not null,
    active number(1) default 1 not null,
    type varchar2(50) default 'bpmn-c7' not null,
    version number(10,0),
    diagram blob,
    updated_by varchar2(100 char),
    constraint pk_mod_processes_diagrams primary key (id),
    constraint uq_mod_processes_diagrams_key unique (processkey)
);

create table MOD_REVINFO (
    rev number(10,0) not null,
    revtstmp number(20,0),
    constraint pk_mod_revinfo primary key (rev)
);

create sequence revinfo_seq start with 1 increment by 1;

create table MOD_PROCESSES_DIAGRAMS_AUD (
    id varchar2(36) not null,
    name varchar2(255),
    processkey varchar2(100),
    description varchar2(150),
    created timestamp,
    updated timestamp,
    active number(1) default 1,
    type varchar2(50) default 'bpmn-c7',
    version number(11),
    diagram_mod number(1) default 0,
    diagram blob,
    updated_by varchar2(100 char),
    rev number(10,0) not null,
    revtype number(6,0),
    constraint pk_mod_processes_diagrams_aud primary key (id, rev),
    constraint fk_mod_processes_diag_aud_rev foreign key (rev) references MOD_REVINFO(rev)
);

create sequence hibernate_sequence
    start with 1
    increment by 1
    minvalue 1
    maxvalue 9223372036854775807
    cache 20;

create table MOD_USER_SESSIONS (
    id varchar2(36) not null,
    user_id varchar2(100 char) not null,
    created_at timestamp default current_timestamp not null,
    expires_at timestamp,
    constraint pk_mod_user_sessions primary key (id)
);

create table MOD_DIAGRAM_USAGE (
    id varchar2(36) not null,
    user_id varchar2(100 char) not null,
    diagram_id varchar2(36) not null,
    session_id varchar2(36) not null,
    opened_at timestamp default current_timestamp not null,
    closed_at timestamp,
    constraint pk_mod_diagram_usage primary key (id),
    constraint fk_mod_diagram_usage_diagram foreign key (diagram_id) references MOD_PROCESSES_DIAGRAMS(id) on delete cascade,
    constraint fk_mod_diagram_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

create table MOD_FORMS (
    id varchar2(36 char) not null,
    description varchar2(150 char),
    created timestamp default current_timestamp,
    updated timestamp default to_timestamp('1970-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'),
    active number(1) default 1 not null,
    form_schema blob not null,
    formid varchar2(100 char) not null,
    version number(11) default 1,
    updated_by varchar2(100 char),
    constraint pk_mod_forms primary key (id),
    constraint uq_mod_forms_formid unique (formid)
);

create table MOD_FORM_USAGE (
    id varchar2(36) not null,
    user_id varchar2(100 char) not null,
    form_id varchar2(36) not null,
    session_id varchar2(36) not null,
    opened_at timestamp default current_timestamp not null,
    closed_at timestamp,
    constraint pk_mod_form_usage primary key (id),
    constraint fk_mod_form_usage_form foreign key (form_id) references MOD_FORMS(id) on delete cascade,
    constraint fk_mod_form_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

insert into ACT_GE_SCHEMA_LOG
values ('1400', CURRENT_TIMESTAMP, 'cibseven-2.2.0');
