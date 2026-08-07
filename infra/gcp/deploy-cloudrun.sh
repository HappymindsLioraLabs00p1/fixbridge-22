#!/usr/bin/env bash
#
# Deploy the FixBridge backend to Google Cloud Run.
#
# Cloud Run — not GKE — is the right fit for this service: one small container that scales to zero
# between demos, so it costs cents rather than the ~$75/month a cluster idles at. The GKE Terraform
# in ../terraform stays available for when the platform actually needs a cluster.
#
# Cloud Build compiles the image in the cloud, so a local Docker daemon is NOT required.
#
# Usage:
#   ./deploy-cloudrun.sh PROJECT_ID [REGION]
#
# Prerequisites (yours to do — they need your Google account):
#   gcloud auth login
#   a project with BILLING ENABLED (Cloud Run's free tier still requires a billing account)
#
set -euo pipefail

PROJECT="${1:?Usage: ./deploy-cloudrun.sh PROJECT_ID [REGION]}"
REGION="${2:-us-east1}"
SERVICE="fixbridge-backend"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

say() { printf "\n\033[1m== %s ==\033[0m\n" "$1"; }

say "Targeting project $PROJECT in $REGION"
gcloud config set project "$PROJECT" >/dev/null

say "Enabling the APIs this needs"
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  --quiet

say "Building and deploying from source (Cloud Build does the Docker work)"
# Secrets are NOT passed on this command line — they are set separately below so they never land
# in your shell history or in Cloud Build logs.
gcloud run deploy "$SERVICE" \
  --source "$REPO_ROOT/backend" \
  --region "$REGION" \
  --platform managed \
  --allow-unauthenticated \
  --port 8080 \
  --memory 1Gi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 4 \
  --timeout 300 \
  --set-env-vars "INTEGRATIONS_STUB_MODE=true,PAYMENTS_STUB_MODE=true,NOTIFICATIONS_STUB_MODE=true,STORAGE_STUB_MODE=true" \
  --quiet

URL="$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')"

say "Deployed"
echo "  $URL"
cat <<EOF

Still to do — these hold secrets, so set them yourself rather than committing them:

  # Database (any Postgres: Cloud SQL, Neon, Supabase, or the one you already have)
  gcloud run services update $SERVICE --region $REGION \\
    --set-env-vars DB_HOST=...,DB_PORT=5432,DB_NAME=fixbridge,DB_USER=...
  gcloud run services update $SERVICE --region $REGION --update-secrets DB_PASSWORD=fixbridge-db-password:latest

  # A long random signing key
  gcloud run services update $SERVICE --region $REGION --update-secrets JWT_SECRET=fixbridge-jwt-secret:latest

  # Let the browser reach it through your Netlify site
  gcloud run services update $SERVICE --region $REGION \\
    --set-env-vars CORS_ORIGINS=https://<your-site>.netlify.app

  # Promote your admin account (it must already be registered)
  gcloud run services update $SERVICE --region $REGION \\
    --set-env-vars BOOTSTRAP_ADMIN_EMAIL=admin@demo.local

To store a secret first:
  printf '%s' 'THE_VALUE' | gcloud secrets create fixbridge-jwt-secret --data-file=-

Then point Netlify at it:
  netlify env:set BACKEND_ORIGIN "$URL" && netlify deploy --build --prod
EOF
