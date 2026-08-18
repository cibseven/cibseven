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
values ('1600', CURRENT_TIMESTAMP, '2.3.0');

-- AI agent audit trail (EU AI Act Art. 12 record-keeping / Art. 26 deployer obligations) --

create table ACT_HI_AGENT_AUDIT (
  ID_ NVARCHAR2(64) not null,
  ROOT_PROC_INST_ID_ NVARCHAR2(64),
  PROC_DEF_KEY_ NVARCHAR2(255),
  PROC_DEF_ID_ NVARCHAR2(64),
  PROC_INST_ID_ NVARCHAR2(64),
  EXECUTION_ID_ NVARCHAR2(64),
  ACT_ID_ NVARCHAR2(255),
  TENANT_ID_ NVARCHAR2(64),
  TIMESTAMP_ TIMESTAMP(6) not null,
  SCHEMA_VERSION_ INTEGER,
  AUDIT_TYPE_ NVARCHAR2(64) not null,
  RUN_ID_ NVARCHAR2(64) not null,
  EVENT_SEQ_ INTEGER,
  PROVIDER_ NVARCHAR2(255),
  MODEL_ NVARCHAR2(255),
  RESPONSE_ID_ NVARCHAR2(255),
  ENDPOINT_ NVARCHAR2(255),
  USER_ID_ NVARCHAR2(255),
  GROUP_IDS_ NVARCHAR2(2000),
  FINISH_REASON_ NVARCHAR2(64),
  DURATION_ NUMBER(19,0),
  ERROR_CLASS_ NVARCHAR2(255),
  MODEL_PARAMS_ NVARCHAR2(2000),
  PAYLOAD_BYTEARRAY_ID_ NVARCHAR2(64),
  REMOVAL_TIME_ TIMESTAMP(6),
  primary key (ID_)
);

create index ACT_IDX_HI_AGENT_RUN on ACT_HI_AGENT_AUDIT(RUN_ID_);
create index ACT_IDX_HI_AGENT_PI on ACT_HI_AGENT_AUDIT(PROC_INST_ID_);
create index ACT_IDX_HI_AGENT_ROOT_PI on ACT_HI_AGENT_AUDIT(ROOT_PROC_INST_ID_);
create index ACT_IDX_HI_AGENT_TYPE on ACT_HI_AGENT_AUDIT(AUDIT_TYPE_);
create index ACT_IDX_HI_AGENT_RM_TIME on ACT_HI_AGENT_AUDIT(REMOVAL_TIME_);
