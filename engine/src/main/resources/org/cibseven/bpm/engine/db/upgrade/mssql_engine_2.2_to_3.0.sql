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

-- The webclient tables of this release are upgraded by mssql_extensions_2.2_to_3.0.sql.

insert into ACT_GE_SCHEMA_LOG
values ('1600', CURRENT_TIMESTAMP, '3.0.0');

-- Cascade deployment deletion deletes job logs by DEPLOYMENT_ID_ (deleteHistoricJobLogByDeploymentId).
-- Without this index that delete scans the whole ACT_HI_JOB_LOG table. Creating it on a large
-- job log takes a while.
CREATE INDEX ACT_IDX_HI_JOB_LOG_DEPLOYMENT ON ACT_HI_JOB_LOG (DEPLOYMENT_ID_);
