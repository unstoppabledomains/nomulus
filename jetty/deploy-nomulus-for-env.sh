#!/bin/bash
# Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
#
# This script prepares the proxy k8s manifest, pushes it to the clusters, and
# kills all running pods to force k8s to create new pods using the just-pushed
# manifest.

# this should probably get moved to nomulus-secrets if there are any merge conflicts in the future

# Abort on error.
set -e

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 alpha|crash|qa [base_domain]}"
  exit 1
fi

environment=${1}
base_domain=${2}
echo "Environment: ${environment}"
echo "Base domain: ${base_domain}"
if [ "${environment}" == "production" ]; then
  project="ud-registry"
else
  project="ud-registry-${environment}-gke"
fi
echo "Project: ${project}"
current_context=$(kubectl config current-context 2>/dev/null || echo "")
echo "Listing clusters in project ${project}..."
set +e
line=$(gcloud container clusters list --project "${project}" 2>&1 | grep nomulus)
cluster_list_exit=$?
set -e
if [ $cluster_list_exit -ne 0 ] || [ -z "$line" ]; then
  echo "ERROR: Failed to list clusters or no matching cluster found"
  echo "Cluster list output:"
  gcloud container clusters list --project "${project}" 2>&1 || true
  exit 1
fi
parts=(${line})
cluster_name=${parts[0]}
location=${parts[1]}
echo "Found cluster: ${cluster_name} in location ${location}"
echo "Updating cluster ${cluster_name} in location ${location}..."
# Try fleet membership first, fall back to regular get-credentials if fleet API not enabled
echo "Getting cluster credentials..."
if ! gcloud container fleet memberships get-credentials "${cluster_name}" --project "${project}" 2>/dev/null; then
  echo "Fleet membership not available, using regular cluster credentials..."
  gcloud container clusters get-credentials "${cluster_name}" --project "${project}" --location "${location}"
fi
echo "Current kubectl context: $(kubectl config current-context 2>/dev/null || echo 'none')"
echo "Verifying cluster connection..."
kubectl cluster-info --request-timeout=10s || (echo "ERROR: Failed to connect to cluster" && exit 1)
echo "Cluster connection verified"

for service in frontend backend pubapi console
do
  echo "Deploying ${service} service..."
  set +e
  sed s/GCP_PROJECT/"${project}"/g "./kubernetes/nomulus-${service}.yaml" | \
    sed s/ENVIRONMENT/"${environment}"/g | \
    sed s/PROXY_ENV/"${environment}"/g | \
    sed s/EPP/"epp"/g | \
    kubectl apply --grace-period=1 -f - 2>&1 | tee /tmp/kubectl-apply-${service}.log
  apply_exit=$?
  set -e
  if [ $apply_exit -ne 0 ]; then
    echo "ERROR: Failed to apply ${service} manifests"
    cat /tmp/kubectl-apply-${service}.log
    # Check if it's a CRD issue
    if grep -q "no matches for kind" /tmp/kubectl-apply-${service}.log; then
      echo "WARNING: CRD may not be installed. Continuing with other resources..."
    else
      exit 1
    fi
  fi
  echo "Restarting ${service} deployment..."
  kubectl rollout restart deployment/${service} || echo "WARNING: Failed to restart ${service} deployment"
  # canary
  echo "Deploying ${service}-canary service..."
  set +e
  sed s/GCP_PROJECT/"${project}"/g "./kubernetes/nomulus-${service}.yaml" | \
    sed s/ENVIRONMENT/"${environment}"/g | \
    sed s/PROXY_ENV/"${environment}_canary"/g | \
    sed s/EPP/"epp-canary"/g | \
    sed s/"${service}"/"${service}-canary"/g | \
    # Undo prober endpoint replacement done in the previous line.
    # The link should stay as /ready/${service}.
    sed s/"ready\/${service}-canary"/"ready\/${service}"/g | \
    kubectl apply --grace-period=1 -f - 2>&1 | tee /tmp/kubectl-apply-${service}-canary.log
  apply_exit=$?
  set -e
  if [ $apply_exit -ne 0 ]; then
    echo "ERROR: Failed to apply ${service}-canary manifests"
    cat /tmp/kubectl-apply-${service}-canary.log
    # Check if it's a CRD issue
    if grep -q "no matches for kind" /tmp/kubectl-apply-${service}-canary.log; then
      echo "WARNING: CRD may not be installed. Continuing with other resources..."
    else
      exit 1
    fi
  fi
  echo "Restarting ${service}-canary deployment..."
  kubectl rollout restart deployment/${service}-canary || echo "WARNING: Failed to restart ${service}-canary deployment"
done
echo "Applying Gateway resources..."
kubectl apply -f "./kubernetes/gateway/nomulus-gateway.yaml" || echo "WARNING: Failed to apply nomulus-gateway.yaml"
if [ -f "./kubernetes/gateway/nomulus-iap-${environment}.yaml" ]; then
  echo "Applying IAP configuration for ${environment}..."
  kubectl apply -f "./kubernetes/gateway/nomulus-iap-${environment}.yaml" || echo "WARNING: Failed to apply nomulus-iap-${environment}.yaml"
else
  echo "WARNING: IAP config file not found: ./kubernetes/gateway/nomulus-iap-${environment}.yaml"
fi
for service in frontend backend console pubapi
do
  echo "Applying route and backend policy for ${service}..."
  sed s/BASE_DOMAIN/"${base_domain}"/g "./kubernetes/gateway/nomulus-route-${service}.yaml" | \
    kubectl apply -f - || echo "WARNING: Failed to apply route for ${service}"
  if [ -f "./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml" ]; then
    sed s/SERVICE/"${service}"/g "./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml" | \
      kubectl apply -f - || echo "WARNING: Failed to apply backend policy for ${service}"
    sed s/SERVICE/"${service}-canary"/g "./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml" | \
      kubectl apply -f - || echo "WARNING: Failed to apply backend policy for ${service}-canary"
  else
    echo "WARNING: Backend policy template not found: ./kubernetes/gateway/nomulus-backend-policy-${environment}.yaml"
  fi
done

echo "Deployment completed successfully"
if [ -n "$current_context" ]; then
  echo "Restoring previous kubectl context: ${current_context}"
  kubectl config use-context "$current_context"
fi
