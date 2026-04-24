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
IF OBJECT_ID('mod_form_usage', 'U') IS NOT NULL DROP TABLE mod_form_usage;
IF OBJECT_ID('mod_diagram_usage', 'U') IS NOT NULL DROP TABLE mod_diagram_usage;
IF OBJECT_ID('mod_forms', 'U') IS NOT NULL DROP TABLE mod_forms;
IF OBJECT_ID('mod_user_sessions', 'U') IS NOT NULL DROP TABLE mod_user_sessions;
IF OBJECT_ID('mod_processes_diagrams_aud', 'U') IS NOT NULL DROP TABLE mod_processes_diagrams_aud;
IF OBJECT_ID('mod_revinfo', 'U') IS NOT NULL DROP TABLE mod_revinfo;
IF OBJECT_ID('mod_processes_diagrams', 'U') IS NOT NULL DROP TABLE mod_processes_diagrams;
IF OBJECT_ID('mod_element_templates', 'U') IS NOT NULL DROP TABLE mod_element_templates;
