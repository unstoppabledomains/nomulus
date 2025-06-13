-- Copyright 2019 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
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
-- Initializes roles and their privileges in the postgres database in Cloud SQL.
-- This script should run once under the **'postgres'** user before any other
-- roles or users are created.

-- Prevent backdoor grants through the implicit 'public' role.
REVOKE ALL PRIVILEGES ON SCHEMA public from public;

-- Create the schema_deployer user, which will be used by the automated schema
-- deployment process. This creation must come before the grants below.
DO
$do$
BEGIN
   IF NOT EXISTS (
      SELECT FROM pg_catalog.pg_roles 
      WHERE rolname = 'schema_deployer'
   ) THEN
      CREATE USER schema_deployer ENCRYPTED PASSWORD :'password';
   END IF;
END
$do$;
-- Comment out line above and uncomment line below if user has been created
-- from Cloud Dashboard:
-- ALTER USER schema_deployer NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE postgres TO schema_deployer;
GRANT CREATE, USAGE ON SCHEMA public TO schema_deployer;
-- The 'postgres' user in Cloud SQL/postgres is not a true super user, and
-- cannot grant access to schema_deployer's objects without taking on its role.
GRANT schema_deployer to postgres;

CREATE ROLE readonly;
GRANT CONNECT ON DATABASE postgres TO readonly;
GRANT USAGE ON SCHEMA public TO readonly;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readonly;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER schema_deployer
  GRANT USAGE, SELECT ON SEQUENCES TO readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER schema_deployer
  GRANT SELECT ON TABLES TO readonly;

CREATE ROLE readwrite;
GRANT CONNECT ON DATABASE postgres TO readwrite;
GRANT USAGE ON SCHEMA public TO readwrite;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readwrite;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER schema_deployer
  GRANT USAGE, SELECT ON SEQUENCES TO readwrite;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readwrite;
ALTER DEFAULT PRIVILEGES
  IN SCHEMA public
  FOR USER schema_deployer
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readwrite;
