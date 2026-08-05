# FixBridge Infrastructure

Docker (local), Terraform (GCP resources), and Kubernetes (GKE deployment). Separate **staging** and
**production** environments — ideally separate GCP projects.

```
infra/
├── docker/            # local full-stack (compose + build)
├── terraform/         # GKE, Cloud SQL, Memorystore, GCS, Secret Manager, Artifact Registry, IAM
└── k8s/
    ├── base/          # namespace, SA (workload identity), config, backend, frontend, ingress
    └── overlays/
        ├── staging/
        └── production/
```

## 1. Local (no cloud)

```bash
docker compose -f infra/docker/docker-compose.yml up --build
```

## 2. Provision GCP with Terraform

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars     # set project_id, region, environment
# Supply app secrets out-of-band (never commit them):
export TF_VAR_app_secrets='{"jwt-secret":"...","stripe-secret-key":"...","stripe-webhook-secret":"...","openai-api-key":"...","claude-api-key":"..."}'
terraform init
terraform apply
```

Creates: VPC + private services access + Cloud NAT, a private VPC-native **GKE** cluster with Workload
Identity, **Cloud SQL** (PostgreSQL 16, private IP), **Memorystore** (Redis), a private **GCS** bucket,
**Artifact Registry**, **Secret Manager** entries, and a backend **service account** with least-privilege
IAM (Secret accessor, Cloud SQL client, bucket object admin).

Read outputs you'll need for k8s:
```bash
terraform output    # gke_get_credentials, db_private_ip, redis_host, media_bucket, artifact_registry, backend_gcp_service_account
```

## 3. Build & push images

```bash
REPO=$(cd infra/terraform && terraform output -raw artifact_registry)
gcloud auth configure-docker us-east1-docker.pkg.dev
docker build -t $REPO/backend:staging  backend  && docker push $REPO/backend:staging
docker build -t $REPO/frontend:staging --build-arg NEXT_PUBLIC_API_URL= frontend && docker push $REPO/frontend:staging
```
(The frontend calls the API same-origin at `/api` via the ingress, so `NEXT_PUBLIC_API_URL` is empty.)

## 4. Deploy to GKE

Install the [Secrets Store CSI driver + GCP provider](https://github.com/GoogleCloudPlatform/secrets-store-csi-driver-provider-gcp)
once per cluster, then fill the `PROJECT_ID` / `REDIS_PRIVATE_IP` / domain placeholders in the overlay
and apply:

```bash
$(cd infra/terraform && terraform output -raw gke_get_credentials)
kubectl apply -k infra/k8s/overlays/staging
```

Both overlays are validated with `kustomize build` (12 objects each): namespace, workload-identity SA,
config, backend + frontend Deployments/Services/HPAs, ingress with Google-managed TLS, and the Secret
Manager CSI mapping.

## Security notes
- Secrets live only in **Secret Manager**, surfaced to pods via the CSI driver — never in images or git.
- The GKE cluster is **private**; Cloud SQL and Redis use **private IP** over VPC peering.
- The backend uses **Workload Identity** (no service-account key files).
- Ingress is **HTTPS-only** with a Google-managed certificate.
