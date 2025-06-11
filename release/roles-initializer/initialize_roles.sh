#!/bin/bash
# Copyright 2019 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Mounted volumes and required files in them:
# - /secrets: cloud_sql_credential.json: Cloud SQL proxy credential
#
# Database login info may be passed in two ways:
# - Save it in the format of "cloud_sql_instance login password" in a file and
#   map the file as /secrets/schema_deployer_credential.dec
# - Provide the content of the credential as command line arguments
set -e
if [ "$#" -le 1 ]; then
  if [ ! -f /secrets/schema_deployer_credential.dec ]; then
    echo "Missing /secrets/schema_deployer_credential.dec"
    exit 1
  fi
  cloud_sql_instance=$(cut -d' ' -f1 /secrets/schema_deployer_credential.dec)
  db_user=$(cut -d' ' -f2 /secrets/schema_deployer_credential.dec)
  db_password=$(cut -d' ' -f3 /secrets/schema_deployer_credential.dec)
elif [ "$#" -ge 3 ]; then
  cloud_sql_instance=$1
  db_user=$2
  db_password=$3
else
  echo "Wrong number of arguments."
  exit 1
fi

cloud_sql_proxy -instances=${cloud_sql_instance}=tcp:5432 \
  --credential_file=/secrets/cloud_sql_credential.json &

set +e
# Wait for cloud_sql_proxy to start:
# first sleep 1 second for the process to launch, then loop until port is ready
# or the proxy process dies.
sleep 1
while ! netstat -an | grep ':5432 ' && pgrep cloud_sql_proxy; do sleep 1; done

if ! pgrep cloud_sql_proxy; then
  echo "Cloud SQL Proxy failed to set up connection."
  exit 1
fi

# Run initialize_roles.sql against the database
PGPASSWORD=$db_password psql -h localhost -p 5432 -U $db_user -d nomulus -f /initialize_roles.sql
if [ $? -ne 0 ]; then
  echo "Failed to initialize roles."
  exit 1
fi
echo "$(date): Successfully initialized roles in ${cloud_sql_instance}."

# Run set_flyway_priveleges.sql against the database
PGPASSWORD=$db_password psql -h localhost -p 5432 -U $db_user -d nomulus -f /set_flyway_privileges.sql
if [ $? -ne 0 ]; then
  echo "Failed to set Flyway privileges."
  exit 1
fi
echo "$(date): Successfully set Flyway privileges in ${cloud_sql_instance}."

# Clean up the background process
pkill cloud_sql_proxy
exit ${migration_result}
