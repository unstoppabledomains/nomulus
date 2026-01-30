# GKE Architecture Documentation

## Overview

This document describes the Kubernetes architecture for Nomulus running on Google Kubernetes Engine (GKE), including network traffic flow, required GCP resources, and deployment dependencies.

## Network Traffic Flow

### HTTP/HTTPS Traffic (Ports 80/443)

```
Internet → Google Cloud HTTP(S) Load Balancer (Global)
         → GKE Gateway API (nomulus)
         → HTTPRoute (hostname/path matching)
         → ServiceImport (multi-cluster service discovery)
         → Kubernetes Service
         → Pods
```

**Components:**
- **Gateway**: `nomulus` (Gateway API resource)
  - Gateway Class: `gke-l7-global-external-managed-mc`
  - Listens on ports 80 (HTTP) and 443 (HTTPS)
  - Uses static IPs: `nomulus-ipv4-address` and `nomulus-ipv6-address`
  - TLS termination with pre-shared certificate: `nomulus`
  - HTTP → HTTPS redirect configured

- **HTTPRoutes**:
  - `frontend`: Host `frontend.BASE_DOMAIN`, Path `/_dr/epp`
  - `backend`: Host `backend.BASE_DOMAIN`, Paths `/_dr/task`, `/_dr/cron`, `/_dr/admin`, `/_dr/epptool`, `/loadtest`
  - `console`: Host `console.BASE_DOMAIN`, Paths `/console`, `/console-api`
  - `pubapi`: Host `pubapi.BASE_DOMAIN`, Paths `/check`, `/rdap`
  - All support canary routing via `canary: true` header

### EPP TCP Traffic (Port 700)

```
Internet → Regional Network Load Balancer (L4 TCP)
         → Node (with pod)
         → EPP Container (port 30002)
```

**Components:**
- **Service**: `EPP` (LoadBalancer type)
  - Port: 700 → 30002
  - Uses static IPs: `EPP-ipv4-main` and `EPP-ipv6-main`
  - Annotation: `cloud.google.com/l4-rbs: enabled` (Regional Backend Service)
  - `externalTrafficPolicy: Local` (preserves source IP)
  - Dual-stack: IPv4 + IPv6

### Proxy TCP Traffic (Ports 30000-30002)

```
Internet → NodePort Service (ports 30000, 30001, 30002)
         → Proxy Pods
```

**Components:**
- **Service**: `proxy-service` (NodePort type)
  - Port 30000: health-check
  - Port 30001: whois
  - Port 30002: epp

## Application Components

### Deployments

1. **frontend**
   - Image: `gcr.io/GCP_PROJECT/nomulus`
   - Container Port: 8080
   - Replicas: 5-20 (HPA)
   - Also includes EPP sidecar container:
     - Image: `gcr.io/GCP_PROJECT/proxy`
     - Container Port: 30002

2. **backend**
   - Image: `gcr.io/GCP_PROJECT/nomulus`
   - Container Port: 8080
   - Replicas: 2-5 (HPA)

3. **console**
   - Image: `gcr.io/GCP_PROJECT/nomulus`
   - Container Port: 8080
   - Replicas: 1-5 (HPA)

4. **pubapi**
   - Image: `gcr.io/GCP_PROJECT/nomulus`
   - Container Port: 8080
   - Replicas: 5-15 (HPA)

5. **proxy** (separate deployment)
   - Image: `us-central1-docker.pkg.dev/ud-registry-{env}/nomulus/proxy`
   - Container Ports: 30000, 30001, 30002
   - Replicas: 10-50 (HPA)

### Services

All HTTP services expose port 80 internally, mapping to container port 8080:
- `frontend` (ClusterIP)
- `backend` (ClusterIP)
- `console` (ClusterIP)
- `pubapi` (ClusterIP)
- `EPP` (LoadBalancer, port 700)
- `proxy-service` (NodePort, ports 30000-30002)

All services export via `ServiceExport` for multi-cluster networking.

## Required GCP Resources

### Static IP Addresses

#### Global IPs (for Gateway)
- **Resource Type**: `google_compute_global_address`
- **Names**:
  - `nomulus-ipv4-address` (IPv4)
  - `nomulus-ipv6-address` (IPv6)
- **Purpose**: HTTP/HTTPS Gateway load balancer
- **Scope**: Global

#### Regional IPs (for EPP LoadBalancer)
- **Resource Type**: `google_compute_address`
- **Names**:
  - `EPP-ipv4-main` (IPv4)
  - `EPP-ipv6-main` (IPv6)
- **Purpose**: EPP TCP LoadBalancer service
- **Scope**: Regional (us-central1)
- **Address Type**: EXTERNAL

### Service Accounts

#### Main Service Account
- **Name**: `nomulus-service-account@GCP_PROJECT.iam.gserviceaccount.com`
- **Purpose**: Used by all nomulus pods (frontend, backend, console, pubapi)
- **Workload Identity**: Bound to Kubernetes ServiceAccount `nomulus`
- **Required Permissions**:
  - Read GCS buckets (for config files)
  - Access Cloud SQL
  - Access Secret Manager (if used)
  - Cloud Logging write

#### Proxy Service Accounts (per environment)
- **Names**:
  - `proxy-service-account@ud-registry-alpha.iam.gserviceaccount.com`
  - `proxy-service-account@ud-registry-crash.iam.gserviceaccount.com`
  - `proxy-service-account@ud-registry-sandbox.iam.gserviceaccount.com`
- **Purpose**: Used by proxy containers for authentication
- **Required Permissions**: Based on proxy requirements

### SSL Certificates

- **Name**: `nomulus`
- **Type**: Pre-shared certificate (uploaded to GCP)
- **Resource Type**: `google_compute_managed_ssl_certificate` or `google_compute_ssl_certificate`
- **Usage**: Referenced in Gateway via `networking.gke.io/pre-shared-certs: nomulus`
- **Domains**: Must cover all hostnames:
  - `frontend.BASE_DOMAIN`
  - `backend.BASE_DOMAIN`
  - `console.BASE_DOMAIN`
  - `pubapi.BASE_DOMAIN`

### GCS Buckets

1. **Deployment Bucket**
   - **Name**: `{PROJECT_ID}-deploy`
   - **Example**: `ud-registry-alpha-deploy`
   - **Purpose**: Stores deployment artifacts, config files
   - **Contents**:
     - `nomulus-config-{env}.yaml`
     - `env-{env}.yaml`
     - `cloud-scheduler-tasks-{env}.xml`
     - `cloud-tasks-queue.xml`
     - Kubernetes manifests
     - JAR files

2. **Deployed Tags Bucket**
   - **Name**: `{PROJECT_ID}-deployed-tags`
   - **Example**: `ud-registry-alpha-deployed-tags`
   - **Purpose**: Tracks deployed versions
   - **Files**:
     - `nomulus-gke.{env}.tag`
     - `nomulus-gke.{env}.versions`

3. **Reporting Bucket**
   - **Name**: `{PROJECT_ID}-reporting`
   - **Example**: `ud-registry-alpha-reporting`
   - **Purpose**: ICANN monthly and spec11 reports
   - **Structure**: `gs://{PROJECT_ID}-reporting/icann/monthly/yyyy-MM`
   - **Structure**: `gs://{PROJECT_ID}-reporting/icann/spec11/yyyy-MM`

4. **Billing Bucket**
   - **Name**: `{PROJECT_ID}-billing`
   - **Example**: `ud-registry-alpha-billing`
   - **Purpose**: Invoice CSVs
   - **Structure**: `gs://{PROJECT_ID}-billing/invoices/yyyy-MM`

### Container Registries

#### Google Container Registry (GCR)
- **Registry**: `gcr.io/GCP_PROJECT`
- **Images**:
  - `gcr.io/GCP_PROJECT/nomulus`
  - `gcr.io/GCP_PROJECT/proxy`
- **Note**: GCR is automatically created when images are pushed

#### Artifact Registry (per environment)
- **Resource Type**: `google_artifact_registry_repository`
- **Repositories**:
  - `us-central1-docker.pkg.dev/ud-registry-alpha/nomulus/proxy`
  - `us-central1-docker.pkg.dev/ud-registry-crash/nomulus/proxy`
  - `us-central1-docker.pkg.dev/ud-registry-sandbox/nomulus/proxy`
- **Location**: `us-central1`
- **Format**: `DOCKER`

### Cloud SQL

- **Instance Connection Name**: `{PROJECT_ID}:{REGION}:{INSTANCE_NAME}`
- **Example**: `ud-registry-alpha:us-central1:shared-8c916947`
- **Type**: PostgreSQL
- **JDBC URL**: `jdbc:postgresql://10.112.1.3:5432/nomulus`
- **Access**: Via private IP from GKE pods

### DNS Records

All domains are **externally resolvable** and must point to the Gateway IPs.

#### Required DNS Records

For each subdomain, create A and AAAA records:

```
frontend.BASE_DOMAIN  → A     → nomulus-ipv4-address
frontend.BASE_DOMAIN  → AAAA  → nomulus-ipv6-address
backend.BASE_DOMAIN   → A     → nomulus-ipv4-address
backend.BASE_DOMAIN   → AAAA  → nomulus-ipv6-address
console.BASE_DOMAIN    → A     → nomulus-ipv4-address
console.BASE_DOMAIN    → AAAA  → nomulus-ipv6-address
pubapi.BASE_DOMAIN     → A     → nomulus-ipv4-address
pubapi.BASE_DOMAIN     → AAAA  → nomulus-ipv6-address
```

**Example** (if BASE_DOMAIN = `ud-registry-alpha.appspot.com`):
- `frontend.ud-registry-alpha.appspot.com`
- `backend.ud-registry-alpha.appspot.com`
- `console.ud-registry-alpha.appspot.com`
- `pubapi.ud-registry-alpha.appspot.com`

**Resource Type**: `google_dns_record_set` (if using Cloud DNS) or external DNS provider

### OAuth 2.0 Clients

1. **Registry Tool Client**
   - **Name**: `nomulus-{env}-desktop`
   - **Example ID**: `221934336020-thc01gu1pc3a53j7pn7to48s7a4e52n9.apps.googleusercontent.com`
   - **Purpose**: OAuth for registry tool
   - **Secret**: Stored as `{OAUTH_CLIENT_SECRET}` placeholder in config

2. **IAP Client**
   - **Name**: `IAP-App-Engine-app`
   - **Example ID**: `221934336020-31ff70mgbcifv7hm6plskrmrjnlkcpp7.apps.googleusercontent.com`
   - **Purpose**: Identity-Aware Proxy authentication
   - **Usage**: Referenced in `nomulus-config-{env}.yaml` as `auth.oauthClientId`

**Resource Type**: Created via Google Cloud Console or `google_iap_client` (if available)

### IAM Bindings

#### Workload Identity
- **Kubernetes ServiceAccount**: `nomulus` (in namespace)
- **GCP ServiceAccount**: `nomulus-service-account@GCP_PROJECT.iam.gserviceaccount.com`
- **Binding**: `iam.gke.io/gcp-service-account` annotation
- **Resource Type**: `google_service_account_iam_binding` with role `roles/iam.workloadIdentityUser`

## Secrets Management

### Secrets Referenced

1. **OAuth Client Secret**
   - **Location**: `nomulus-config-{env}.yaml` files
   - **Placeholder**: `{OAUTH_CLIENT_SECRET}`
   - **Usage**: Registry tool OAuth authentication
   - **Storage**: Likely in GCP Secret Manager or injected during deployment

2. **PowerDNS API Key**
   - **Location**: `env-{env}.yaml` files
   - **Placeholder**: `{POWER_DNS_API_KEY}`
   - **Usage**: PowerDNS integration
   - **Storage**: Likely in GCP Secret Manager or injected during deployment

3. **Cloud Build Credential**
   - **Secret Name**: `nomulus-tool-cloudbuild-credential`
   - **Location**: GCP Secret Manager
   - **Usage**: Cloud Build pipeline authentication
   - **Resource Type**: `google_secret_manager_secret` + `google_secret_manager_secret_version`

### Secret Injection Pattern

**Important**: The Kubernetes manifests do **not** show explicit Secret or ConfigMap mounting. The application likely:

1. **Reads config from GCS**: Config files are stored in `gs://{PROJECT_ID}-deploy/` and read at runtime using service account credentials
2. **Uses Workload Identity**: Pods authenticate to GCP services (GCS, Secret Manager, Cloud SQL) via the service account without explicit secret mounting
3. **Template substitution**: Placeholders like `{OAUTH_CLIENT_SECRET}` may be replaced during deployment or read from Secret Manager at runtime

### Required Secret Manager Resources

```hcl
# Cloud Build credential
google_secret_manager_secret.nomulus_tool_cloudbuild_credential
google_secret_manager_secret_version.nomulus_tool_cloudbuild_credential

# Per-environment secrets (if stored in Secret Manager)
google_secret_manager_secret.oauth_client_secret_{env}
google_secret_manager_secret_version.oauth_client_secret_{env}

google_secret_manager_secret.powerdns_api_key_{env}
google_secret_manager_secret_version.powerdns_api_key_{env}

# IAM permissions for service account
google_secret_manager_secret_iam_member (grant secretAccessor role)
```

## Terraform Resource Summary

### Complete Terraform Resources List

```hcl
# Static IPs
resource "google_compute_global_address" "nomulus_ipv4" {
  name = "nomulus-ipv4-address"
}

resource "google_compute_global_address" "nomulus_ipv6" {
  name         = "nomulus-ipv6-address"
  address_type = "EXTERNAL"
  ip_version    = "IPV6"
}

resource "google_compute_address" "epp_ipv4" {
  name         = "EPP-ipv4-main"
  region       = "us-central1"
  address_type = "EXTERNAL"
}

resource "google_compute_address" "epp_ipv6" {
  name         = "EPP-ipv6-main"
  region       = "us-central1"
  address_type = "EXTERNAL"
  ip_version    = "IPV6"
}

# Service Accounts
resource "google_service_account" "nomulus" {
  account_id   = "nomulus-service-account"
  display_name = "Nomulus Service Account"
}

resource "google_service_account" "proxy" {
  for_each     = toset(["alpha", "crash", "sandbox"])
  account_id   = "proxy-service-account"
  display_name = "Proxy Service Account"
  project      = "ud-registry-${each.value}"
}

# Workload Identity Binding
resource "google_service_account_iam_binding" "nomulus_workload_identity" {
  service_account_id = google_service_account.nomulus.name
  role               = "roles/iam.workloadIdentityUser"
  members = [
    "serviceAccount:${var.project_id}.svc.id.goog[${var.namespace}/nomulus]"
  ]
}

# SSL Certificates
resource "google_compute_managed_ssl_certificate" "nomulus" {
  name = "nomulus"
  managed {
    domains = [
      "frontend.${var.base_domain}",
      "backend.${var.base_domain}",
      "console.${var.base_domain}",
      "pubapi.${var.base_domain}"
    ]
  }
}

# GCS Buckets
resource "google_storage_bucket" "deploy" {
  name     = "${var.project_id}-deploy"
  location = "US"
}

resource "google_storage_bucket" "deployed_tags" {
  name     = "${var.project_id}-deployed-tags"
  location = "US"
}

resource "google_storage_bucket" "reporting" {
  name     = "${var.project_id}-reporting"
  location = "US"
}

resource "google_storage_bucket" "billing" {
  name     = "${var.project_id}-billing"
  location = "US"
}

# Artifact Registry
resource "google_artifact_registry_repository" "proxy" {
  for_each  = toset(["alpha", "crash", "sandbox"])
  location  = "us-central1"
  repository_id = "nomulus"
  format    = "DOCKER"
  project   = "ud-registry-${each.value}"
}

# Secret Manager
resource "google_secret_manager_secret" "nomulus_tool_cloudbuild_credential" {
  secret_id = "nomulus-tool-cloudbuild-credential"
}

resource "google_secret_manager_secret_version" "nomulus_tool_cloudbuild_credential" {
  secret      = google_secret_manager_secret.nomulus_tool_cloudbuild_credential.id
  secret_data = var.cloudbuild_credential_json
}

# DNS Records (if using Cloud DNS)
resource "google_dns_record_set" "frontend_a" {
  name         = "frontend.${var.base_domain}."
  type         = "A"
  ttl          = 300
  managed_zone = var.dns_zone_name
  rrdatas      = [google_compute_global_address.nomulus_ipv4.address]
}

resource "google_dns_record_set" "frontend_aaaa" {
  name         = "frontend.${var.base_domain}."
  type         = "AAAA"
  ttl          = 300
  managed_zone = var.dns_zone_name
  rrdatas      = [google_compute_global_address.nomulus_ipv6.address]
}

# Repeat for backend, console, pubapi...
```

## Deployment Dependencies

### Prerequisites (in order)

1. **GCP Project** with billing enabled
2. **Static IP Addresses** (global and regional)
3. **Service Accounts** with appropriate IAM roles
4. **Workload Identity** binding configured
5. **GCS Buckets** created
6. **SSL Certificate** created and validated
7. **DNS Records** pointing to Gateway IPs
8. **Artifact Registry** repositories (for proxy images)
9. **Cloud SQL** instance (if not already exists)
10. **Secret Manager** secrets created
11. **OAuth Clients** created
12. **GKE Cluster** with Gateway API enabled
13. **Multi-Cluster Services** enabled (for ServiceImport)

### IAM Permissions Required

#### Service Account: `nomulus-service-account`
- `roles/storage.objectViewer` on deployment buckets
- `roles/cloudsql.client` on Cloud SQL instance
- `roles/secretmanager.secretAccessor` on secrets (if using Secret Manager)
- `roles/logging.logWriter` for Cloud Logging
- Additional permissions as needed by Nomulus application

#### Service Account: `proxy-service-account`
- Permissions as required by proxy application

## Notes

- The Gateway uses **multi-cluster** networking (`-mc` suffix), so ensure GKE Multi-Cluster Services is enabled
- All HTTP services use **ServiceExport** for cross-cluster service discovery
- The EPP LoadBalancer uses **Regional Backend Service** (`l4-rbs`) for better source IP preservation
- Config files are stored in GCS and likely read at runtime by the application
- Placeholders in config files (`{OAUTH_CLIENT_SECRET}`, `{POWER_DNS_API_KEY}`) need to be resolved during deployment or at runtime
- The application reads configuration from the `ENVIRONMENT` argument passed to containers
