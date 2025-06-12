#!/bin/bash

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
