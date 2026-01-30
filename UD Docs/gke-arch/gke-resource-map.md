# Kubernetes and GCP Resource Map

This document maps all Kubernetes and GCP resources in the Nomulus deployment architecture.

## Architecture Overview

The Nomulus deployment uses two main networking paths:
1. **L4 Load Balancer (Regional)** - For EPP service (TCP port 700)
2. **L7 Global Load Balancer (Gateway API)** - For HTTP/HTTPS services (frontend, backend, console, pubapi)

---

## Complete Architecture Diagram

See [gke-complete-architecture.mermaid](./gke-complete-architecture.mermaid) for the complete architecture diagram.

---

## 1. EPP Service - L4 Regional Load Balancer Path

### GCP Resources (Regional - us-central1)

```mermaid
graph TD
    EPP_IP["GCP Static IP Address<br/>(Regional)<br/>Name: EPP-ipv4-main<br/>Type: EXTERNAL, IPv4<br/>Region: us-central1"]
    
    EPP_FR["GCP Forwarding Rule<br/>(Regional)<br/>Type: TCP Forwarding Rule<br/>Port: 700<br/>Description:<br/>networking.gke.io/service-name: default/epp<br/>networking.gke.io/api-version: ga"]
    
    EPP_BS["GCP Regional Backend Service<br/>Name: k8s2-*-default-epp-*<br/>Type: Regional Backend Service L4 RBS<br/>Protocol: TCP<br/>Description:<br/>networking.gke.io/service-name: default/epp<br/>networking.gke.io/api-version: ga"]
    
    EPP_NEG_A["GCP Network Endpoint Group<br/>Zone: us-central1-a<br/>Type: GCE_VM_IP_PORT"]
    
    EPP_NEG_B["GCP Network Endpoint Group<br/>Zone: us-central1-b<br/>Type: GCE_VM_IP_PORT"]
    
    EPP_NEG_C["GCP Network Endpoint Group<br/>Zone: us-central1-c<br/>Type: GCE_VM_IP_PORT"]
    
    EPP_HC["GCP Health Check<br/>Name: k8s2-*-default-epp-*<br/>Type: Regional Health Check"]
    
    EPP_IP --> EPP_FR
    EPP_FR --> EPP_BS
    EPP_BS --> EPP_NEG_A
    EPP_BS --> EPP_NEG_B
    EPP_BS --> EPP_NEG_C
    EPP_BS --> EPP_HC
    
    %% Color scheme 4: Unstoppable Domains inspired gradient - GCP resources
    classDef gcp fill:#4A7FFF,stroke:#37B2E3,stroke-width:2px,color:#ffffff
    class EPP_IP,EPP_FR,EPP_BS,EPP_NEG_A,EPP_NEG_B,EPP_NEG_C,EPP_HC gcp
```

### Kubernetes Resources

```mermaid
graph TD
    EPP_SVC["Kubernetes Service<br/>Name: epp<br/>Namespace: default<br/>Type: LoadBalancer<br/><br/>Annotations:<br/>- cloud.google.com/l4-rbs: enabled<br/>- networking.gke.io/load-balancer-ip-addresses:<br/>  EPP-ipv6-main,EPP-ipv4-main<br/>- networking.gke.io/weighted-load-balancing:<br/>  pods-per-node<br/><br/>Spec:<br/>- externalTrafficPolicy: Local<br/>- ipFamilies: [IPv4, IPv6]<br/>- ipFamilyPolicy: RequireDualStack<br/>- ports: [{port: 700, targetPort: epp}]<br/>- selector: {service: frontend}"]
    
    EPP_DEPLOY["Kubernetes Deployment<br/>Name: frontend<br/>Namespace: default<br/>Labels: {service: frontend}"]
    
    EPP_PODS["Kubernetes Pods<br/>Labels: {service: frontend}<br/><br/>Containers:<br/>- frontend (port 8080/http)<br/>- EPP (port 30002/epp)"]
    
    EPP_HPA["HorizontalPodAutoscaler<br/>Name: frontend"]
    
    EPP_SA["ServiceAccount<br/>Name: nomulus"]
    
    EPP_EXPORT["ServiceExport<br/>Name: frontend<br/>(for multi-cluster)"]
    
    EPP_SVC --> EPP_DEPLOY
    EPP_DEPLOY --> EPP_PODS
    EPP_DEPLOY --> EPP_HPA
    EPP_DEPLOY --> EPP_SA
    EPP_SVC --> EPP_EXPORT
    
    %% Color scheme 4: Unstoppable Domains inspired gradient
    classDef service fill:#B080FF,stroke:#8E5CFF,stroke-width:2px,color:#ffffff
    classDef deployment fill:#2DD4BF,stroke:#14B8A6,stroke-width:2px,color:#ffffff
    classDef pod fill:#6B4DFF,stroke:#4A7FFF,stroke-width:2px,color:#ffffff
    classDef policy fill:#37B2E3,stroke:#2A9FD4,stroke-width:2px,color:#ffffff
    class EPP_SVC service
    class EPP_DEPLOY deployment
    class EPP_PODS pod
    class EPP_HPA,EPP_SA,EPP_EXPORT policy
```

---

## 2. HTTP Services - L7 Global Load Balancer Path (Gateway API)

### GCP Resources (Global)

```mermaid
graph TD
    GATEWAY_IP["GCP Static IP Address<br/>(Global)<br/>Name: nomulus-ipv4-address<br/>Type: EXTERNAL, IPv4, Global"]
    
    GATEWAY_FR["GCP Global Forwarding Rule<br/>Type: HTTP(S) Load Balancer<br/>Ports: 80, 443<br/>Created by: GKE Gateway Controller"]
    
    GATEWAY_URLMAP["GCP URL Map<br/>Created by: GKE Gateway Controller<br/><br/>Routes:<br/>- frontend.BASE_DOMAIN/_dr/epp<br/>- backend.BASE_DOMAIN/_dr/{task,cron,admin,<br/>  epptool,loadtest,mosapi}<br/>- console.BASE_DOMAIN/{console-api,console}<br/>- pubapi.BASE_DOMAIN/{check,rdap}"]
    
    GATEWAY_BS["GCP Backend Service (Global)<br/>Created by: GKE Gateway Controller<br/>Type: Global Backend Service<br/>Protocol: HTTP<br/>Backend Policy: Applied per service"]
    
    BS_FRONTEND["GCP Backend Service<br/>(Service: frontend)"]
    
    BS_BACKEND["GCP Backend Service<br/>(Service: backend)"]
    
    BS_CONSOLE["GCP Backend Service<br/>(Service: console)"]
    
    BS_PUBAPI["GCP Backend Service<br/>(Service: pubapi)"]
    
    GATEWAY_IP --> GATEWAY_FR
    GATEWAY_FR --> GATEWAY_URLMAP
    GATEWAY_URLMAP --> GATEWAY_BS
    GATEWAY_BS --> BS_FRONTEND
    GATEWAY_BS --> BS_BACKEND
    GATEWAY_BS --> BS_CONSOLE
    GATEWAY_BS --> BS_PUBAPI
    
    %% Color scheme 4: Unstoppable Domains inspired gradient - GCP resources
    classDef gcp fill:#4A7FFF,stroke:#37B2E3,stroke-width:2px,color:#ffffff
    class GATEWAY_IP,GATEWAY_FR,GATEWAY_URLMAP,GATEWAY_BS,BS_FRONTEND,BS_BACKEND,BS_CONSOLE,BS_PUBAPI gcp
```

### Kubernetes Resources

```mermaid
graph TD
    GATEWAY["Kubernetes Gateway<br/>Name: nomulus<br/>API: gateway.networking.k8s.io/v1beta1<br/>GatewayClassName: gke-l7-global-external-managed-mc<br/><br/>Addresses:<br/>- type: NamedAddress, value: nomulus-ipv4-address<br/>- type: NamedAddress, value: nomulus-ipv6-address<br/><br/>Listeners:<br/>- http (port 80)<br/>- https (port 443, TLS terminate, cert: nomulus)"]
    
    HTTPROUTE["Kubernetes HTTPRoute<br/>Name: frontend<br/>Parent: Gateway/nomulus (https listener)<br/>Hostnames: frontend.BASE_DOMAIN<br/><br/>Rules:<br/>- Path: /_dr/epp → ServiceImport/frontend:80<br/>- Path: /_dr/epp + Header canary=true →<br/>  ServiceImport/frontend-canary:80"]
    
    SVCIMPORT["Kubernetes ServiceImport<br/>Name: frontend<br/>API: net.gke.io/v1<br/>(Exported from Service via ServiceExport)"]
    
    SVC["Kubernetes Service<br/>Name: frontend<br/>Namespace: default<br/>Type: ClusterIP<br/>Ports: [{port: 80, targetPort: http}]<br/>Selector: {service: frontend}"]
    
    DEPLOY["Kubernetes Deployment<br/>Name: frontend<br/>Namespace: default<br/>Labels: {service: frontend}"]
    
    PODS["Kubernetes Pods<br/>Labels: {service: frontend}<br/>Containers:<br/>- frontend (port 8080/http)"]
    
    HPA["HorizontalPodAutoscaler<br/>Name: frontend"]
    
    SA["ServiceAccount<br/>Name: nomulus"]
    
    EXPORT["ServiceExport<br/>Name: frontend"]
    
    HCP["HealthCheckPolicy<br/>Name: frontend"]
    
    BPPOLICY["GCPBackendPolicy<br/>Name: frontend"]
    
    GATEWAY --> HTTPROUTE
    HTTPROUTE --> SVCIMPORT
    SVCIMPORT --> SVC
    SVC --> DEPLOY
    DEPLOY --> PODS
    DEPLOY --> HPA
    DEPLOY --> SA
    SVC --> EXPORT
    HTTPROUTE --> HCP
    HTTPROUTE --> BPPOLICY
    
    %% Color scheme 4: Unstoppable Domains inspired gradient
    classDef gateway fill:#E1C7FF,stroke:#C9A3FF,stroke-width:2px,color:#6B2D9F
    classDef route fill:#C9A3FF,stroke:#B080FF,stroke-width:2px,color:#ffffff
    classDef serviceimport fill:#8E5CFF,stroke:#6B4DFF,stroke-width:2px,color:#ffffff
    classDef service fill:#B080FF,stroke:#8E5CFF,stroke-width:2px,color:#ffffff
    classDef deployment fill:#2DD4BF,stroke:#14B8A6,stroke-width:2px,color:#ffffff
    classDef pod fill:#6B4DFF,stroke:#4A7FFF,stroke-width:2px,color:#ffffff
    classDef policy fill:#37B2E3,stroke:#2A9FD4,stroke-width:2px,color:#ffffff
    class GATEWAY gateway
    class HTTPROUTE route
    class SVCIMPORT serviceimport
    class SVC service
    class DEPLOY deployment
    class PODS pod
    class HPA,SA,EXPORT,HCP,BPPOLICY policy
```

### Additional Gateway Routes

**Backend Service Route:**
- HTTPRoute: `backend`
- Hostname: `backend.BASE_DOMAIN`
- Paths: `/_dr/task`, `/_dr/cron`, `/_dr/admin`, `/_dr/epptool`, `/_dr/loadtest`, `/_dr/mosapi`
- Targets: ServiceImport/backend:80 (with canary support)

**Console Service Route:**
- HTTPRoute: `console`
- Hostname: `console.BASE_DOMAIN`
- Paths: `/console-api`, `/console`
- Targets: ServiceImport/console:80 (with canary support)

**PubAPI Service Route:**
- HTTPRoute: `pubapi`
- Hostname: `pubapi.BASE_DOMAIN`
- Paths: `/check`, `/rdap`
- Targets: ServiceImport/pubapi:80 (with canary support)

---

## 3. Service-Specific Resources

### Frontend Service
- **Kubernetes Resources:**
  - Deployment: `frontend`
  - Service: `frontend` (ClusterIP)
  - Service: `epp` (LoadBalancer - L4)
  - ServiceExport: `frontend`
  - HorizontalPodAutoscaler: `frontend`
  - HTTPRoute: `frontend`
  - HealthCheckPolicy: `frontend`, `frontend-canary`
  - GCPBackendPolicy: `frontend`, `frontend-canary`

### Backend Service
- **Kubernetes Resources:**
  - Deployment: `backend`
  - Service: `backend` (ClusterIP)
  - ServiceExport: `backend`
  - HorizontalPodAutoscaler: `backend`
  - HTTPRoute: `backend`
  - HealthCheckPolicy: `backend`, `backend-canary`
  - GCPBackendPolicy: `backend`, `backend-canary`

### Console Service
- **Kubernetes Resources:**
  - Deployment: `console`
  - Service: `console` (ClusterIP)
  - ServiceExport: `console`
  - HorizontalPodAutoscaler: `console`
  - HTTPRoute: `console`
  - HealthCheckPolicy: `console`, `console-canary`
  - GCPBackendPolicy: `console`, `console-canary`

### PubAPI Service
- **Kubernetes Resources:**
  - Deployment: `pubapi`
  - Service: `pubapi` (ClusterIP)
  - ServiceExport: `pubapi`
  - HorizontalPodAutoscaler: `pubapi`
  - HTTPRoute: `pubapi`
  - HealthCheckPolicy: `pubapi`, `pubapi-canary`
  - GCPBackendPolicy: `pubapi`, `pubapi-canary`

---

## 4. Canary Deployments

Each service has a canary variant:
- Deployment: `{service}-canary`
- Service: `{service}-canary` (ClusterIP)
- ServiceExport: `{service}-canary`
- HTTPRoute: Routes with `canary=true` header → canary ServiceImport
- HealthCheckPolicy: `{service}-canary`
- GCPBackendPolicy: `{service}-canary`

---

## 5. Required GCP Static IP Addresses

### Regional IPs (for L4 Load Balancer)
1. **EPP-ipv4-main** (us-central1, IPv4)
2. **EPP-ipv6-main** (us-central1, IPv6)

### Global IPs (for L7 Load Balancer)
1. **nomulus-ipv4-address** (global, IPv4)
2. **nomulus-ipv6-address** (global, IPv6)

---

## 6. Resource Naming Conventions

### GCP Resources (Auto-generated by GKE)
- Forwarding Rules: `k8s2-<cluster-hash>-<namespace>-<service>-<hash>`
- Backend Services: `k8s2-<cluster-hash>-<namespace>-<service>-<hash>`
- Network Endpoint Groups: `k8s2-<cluster-hash>-<namespace>-<service>-<hash>`
- Health Checks: `k8s2-<cluster-hash>-<namespace>-<service>-<hash>`

### Kubernetes Resources (Defined in Manifests)
- Deployments: `{service}`, `{service}-canary`
- Services: `{service}`, `{service}-canary`, `epp`
- HTTPRoutes: `{service}` (frontend, backend, console, pubapi)
- Gateway: `nomulus`
- ServiceExports: `{service}`, `{service}-canary`
- HealthCheckPolicy: `{service}`, `{service}-canary`
- GCPBackendPolicy: `{service}`, `{service}-canary` (generated from template)

---

## 7. Resource Dependencies

```mermaid
graph TD
    STATIC_IPS["Static IP Addresses"]
    
    FR_GCP["Forwarding Rules (GCP)"]
    
    BS_GCP["Backend Services (GCP)"]
    
    NEG_GCP["Network Endpoint Groups (GCP)"]
    
    GKE_NODES["GKE Nodes/Pods"]
    
    GATEWAY_K8S["Gateway (K8s)"]
    
    HTTPROUTES_K8S["HTTPRoutes (K8s)"]
    
    SVCIMPORTS_K8S["ServiceImports (K8s)"]
    
    SERVICES_K8S["Services (K8s)"]
    
    DEPLOYS_K8S["Deployments (K8s)"]
    
    PODS_K8S["Pods (K8s)"]
    
    HCP_K8S["HealthCheckPolicy (K8s)"]
    
    BPPOLICY_K8S["GCPBackendPolicy (K8s)"]
    
    BS_GCP2["Backend Services (GCP)"]
    
    STATIC_IPS --> FR_GCP
    FR_GCP --> BS_GCP
    BS_GCP --> NEG_GCP
    NEG_GCP --> GKE_NODES
    
    STATIC_IPS --> GATEWAY_K8S
    GATEWAY_K8S --> HTTPROUTES_K8S
    HTTPROUTES_K8S --> SVCIMPORTS_K8S
    SVCIMPORTS_K8S --> SERVICES_K8S
    SERVICES_K8S --> DEPLOYS_K8S
    DEPLOYS_K8S --> PODS_K8S
    HTTPROUTES_K8S --> HCP_K8S
    HTTPROUTES_K8S --> BPPOLICY_K8S
    BPPOLICY_K8S --> BS_GCP2
    
    %% Color scheme 4: Unstoppable Domains inspired gradient
    classDef gcp fill:#4A7FFF,stroke:#37B2E3,stroke-width:2px,color:#ffffff
    classDef gateway fill:#E1C7FF,stroke:#C9A3FF,stroke-width:2px,color:#6B2D9F
    classDef route fill:#C9A3FF,stroke:#B080FF,stroke-width:2px,color:#ffffff
    classDef service fill:#B080FF,stroke:#8E5CFF,stroke-width:2px,color:#ffffff
    classDef serviceimport fill:#8E5CFF,stroke:#6B4DFF,stroke-width:2px,color:#ffffff
    classDef deployment fill:#2DD4BF,stroke:#14B8A6,stroke-width:2px,color:#ffffff
    classDef pod fill:#6B4DFF,stroke:#4A7FFF,stroke-width:2px,color:#ffffff
    classDef policy fill:#37B2E3,stroke:#2A9FD4,stroke-width:2px,color:#ffffff
    class STATIC_IPS,FR_GCP,BS_GCP,NEG_GCP,BS_GCP2 gcp
    class GATEWAY_K8S gateway
    class HTTPROUTES_K8S route
    class SVCIMPORTS_K8S serviceimport
    class SERVICES_K8S service
    class DEPLOYS_K8S deployment
    class PODS_K8S,GKE_NODES pod
    class HCP_K8S,BPPOLICY_K8S policy
```

---

## Notes

1. **L4 vs L7 Load Balancing:**
   - EPP service uses L4 Regional Load Balancer (TCP, port 700)
   - All other services use L7 Global Load Balancer (HTTP/HTTPS)

2. **Multi-Cluster Networking:**
   - ServiceExport resources enable cross-cluster service discovery
   - ServiceImport resources reference exported services

3. **Canary Deployments:**
   - All services support canary deployments via header-based routing
   - Canary services are separate deployments with `-canary` suffix

4. **Static IP Requirements:**
   - All IP addresses must be created before deploying services
   - Regional IPs for L4, Global IPs for L7

5. **Backend Policy:**
   - GCPBackendPolicy is applied per service (and canary)
   - Template uses `SERVICE` placeholder replaced during deployment
