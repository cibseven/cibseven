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
    id nvarchar(36) not null,
    active bit default 1 not null,
    version int default 1,
    template_id nvarchar(100) not null,
    name nvarchar(200) not null,
    description nvarchar(max),
    origin nvarchar(50) not null,
    content nvarchar(max),
    created_at datetime2 default sysutcdatetime() not null,
    updated_at datetime2 default sysutcdatetime() not null,
    created_by nvarchar(100),
    updated_by nvarchar(100),
    constraint pk_mod_element_templates primary key (id),
    constraint uq_mod_element_templates_template_id unique (template_id)
);

create table MOD_PROCESSES_DIAGRAMS (
    id nvarchar(36) not null,
    name nvarchar(255) not null,
    processkey nvarchar(100) not null,
    description nvarchar(150),
    created datetime2 default sysutcdatetime() not null,
    updated datetime2 default sysutcdatetime() not null,
    active bit default 1 not null,
    type nvarchar(50) not null default 'bpmn-c7',
    version int,
    diagram varbinary(max),
    updated_by nvarchar(100),
    constraint pk_mod_processes_diagrams primary key (id),
    constraint uq_mod_processes_diagrams_processkey unique (processkey)
);

create table MOD_REVINFO (
    rev int identity(1,1) not null,
    revtstmp bigint,
    constraint pk_mod_revinfo primary key (rev)
);

create table MOD_PROCESSES_DIAGRAMS_AUD (
    id nvarchar(36) not null,
    name nvarchar(255),
    processkey nvarchar(100),
    description nvarchar(150),
    created datetime2,
    updated datetime2,
    active bit default 1,
    type nvarchar(50) default 'bpmn-c7',
    version int,
    diagram_mod bit default 0,
    diagram varbinary(max),
    updated_by nvarchar(100),
    rev int not null,
    revtype smallint,
    constraint pk_mod_processes_diagrams_aud primary key (id, rev),
    constraint fk_mod_processes_diagrams_aud_rev foreign key (rev) references MOD_REVINFO(rev)
);

create sequence hibernate_sequence
    start with 1
    increment by 1
    minvalue 1
    maxvalue 9223372036854775807
    cache 20;

create table MOD_USER_SESSIONS (
    id nvarchar(36) not null,
    user_id nvarchar(100) not null,
    created_at datetime2 default sysutcdatetime() not null,
    expires_at datetime2,
    constraint pk_mod_user_sessions primary key (id)
);

create table MOD_DIAGRAM_USAGE (
    id nvarchar(36) not null,
    user_id nvarchar(100) not null,
    diagram_id nvarchar(36) not null,
    session_id nvarchar(36) not null,
    opened_at datetime2 default sysutcdatetime() not null,
    closed_at datetime2,
    constraint pk_mod_diagram_usage primary key (id),
    constraint fk_mod_diagram_usage_diagram foreign key (diagram_id) references MOD_PROCESSES_DIAGRAMS(id) on delete cascade,
    constraint fk_mod_diagram_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

create table MOD_FORMS (
    id nvarchar(36) not null,
    description nvarchar(150),
    created datetime2 default sysutcdatetime(),
    updated datetime2 default '1970-01-01 00:00:00',
    active bit default 1 not null,
    form_schema varbinary(max) not null,
    formid nvarchar(100) not null,
    version int default 1,
    updated_by nvarchar(100),
    constraint pk_mod_forms primary key (id),
    constraint uq_mod_forms_formid unique (formid)
);

create table MOD_FORM_USAGE (
    id nvarchar(36) not null,
    user_id nvarchar(100) not null,
    form_id nvarchar(36) not null,
    session_id nvarchar(36) not null,
    opened_at datetime2 default sysutcdatetime() not null,
    closed_at datetime2,
    constraint pk_mod_form_usage primary key (id),
    constraint fk_mod_form_usage_form foreign key (form_id) references MOD_FORMS(id) on delete cascade,
    constraint fk_mod_form_usage_session foreign key (session_id) references MOD_USER_SESSIONS(id) on delete cascade
);

insert into ACT_GE_SCHEMA_LOG
values ('1400', CURRENT_TIMESTAMP, 'cibseven-2.2.0');
