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

set -e
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 "<env>" "<tools_credential>" '[{email:<email>,role:<role>,admin:<admin>}]'"
  exit 1
fi

env="$1"
tools_credential="$2"
users="$3"

if [ -z "$users" ] || [ "$users" = "[]" ]; then
  echo "No users to process. Exiting."
  exit 0
fi

echo "$users" | jq -c '.[]' | while read obj; do
  email=$(echo "$obj" | jq -r '.email')
  role=$(echo "$obj" | jq -r '.role')
  admin=$(echo "$obj" | jq -r '.admin')

  if [ -z "$email" ] || [ -z "$role" ] || [ -z "$admin" ]; then
    echo "Invalid user object: $obj. Must contain email, role, and admin."
    exit 1
  fi

  echo "$(date): Adding user $email with role $role and admin status $admin on $env."
  
  java -jar /nomulus.jar -e ${env} \
    --credential "${tools_credential}" \
    create_user --email "${email}" --global_role "${role}" --admin "${admin}" --force
done
