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
DROP TABLE mod_form_usage CASCADE CONSTRAINTS;
DROP TABLE mod_diagram_usage CASCADE CONSTRAINTS;
DROP TABLE mod_forms CASCADE CONSTRAINTS;
DROP TABLE mod_user_sessions CASCADE CONSTRAINTS;
DROP TABLE mod_processes_diagrams_aud CASCADE CONSTRAINTS;
DROP TABLE mod_revinfo CASCADE CONSTRAINTS;
DROP TABLE mod_processes_diagrams CASCADE CONSTRAINTS;
DROP TABLE mod_element_templates CASCADE CONSTRAINTS;

-- Drop sequences
DROP SEQUENCE mod_revinfo_seq;
