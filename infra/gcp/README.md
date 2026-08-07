# Deploying the backend to Google Cloud

## Which GCP service — and why not the Terraform

This repo has **two** GCP paths. Pick deliberately:

| Path | What it is | Cost at demo traffic | Use when |
|---|---|---|---|
| **Cloud Run** (`deploy-cloudrun.sh`) | One container, scales to zero | **~$0–5/mo** | ✅ Now. One small service. |
| **GKE** (`../terraform`) | Full Kubernetes cluster | **~$75+/mo idle** | Later — many services, custom networking |

The GKE Terraform was written for the architecture phase and still works, but a cluster idling at
$75/month to run one container is the wrong trade for this stage. Cloud Run runs the *same*
`backend/Dockerfile`, so moving between them costs no application code.

## Before you start

**Cloud Run's free tier still requires a billing account with a card.** That is Google's rule, not a
choice in this repo. If you don't want to attach one, Render's free tier needs no card and
`../../render.yaml` already targets it.

You also need, once:

```bash
gcloud auth login
gcloud projects create fixbridge-prod   # or use an existing project
# then enable billing on that project in the Cloud console
```

## Deploy

```bash
./infra/gcp/deploy-cloudrun.sh YOUR_PROJECT_ID us-east1
```

Cloud Build compiles the image in the cloud, so **you do not need a running Docker daemon**.

The script deploys with every integration stubbed, which means it comes up working with no external
keys. Turn them on one at a time afterwards (see below).

## A database

Cloud Run has no database of its own. Any Postgres works — the app builds its JDBC URL from parts:

| Option | Cost | Notes |
|---|---|---|
| **Neon / Supabase free** | **$0** | Recommended to start; no card |
| **Cloud SQL** | ~$9–10/mo | Tightest GCP integration; needs the Cloud SQL connector |

```bash
gcloud run services update fixbridge-backend --region us-east1 \
  --set-env-vars DB_HOST=...,DB_PORT=5432,DB_NAME=fixbridge,DB_USER=...
```

Flyway creates the schema and seeds pilot pricing on first boot.

## Secrets

Never pass secrets as `--set-env-vars` — they end up in shell history and deploy logs. Use Secret
Manager:

```bash
printf '%s' 'A_LONG_RANDOM_STRING' | gcloud secrets create fixbridge-jwt-secret --data-file=-
gcloud run services update fixbridge-backend --region us-east1 \
  --update-secrets JWT_SECRET=fixbridge-jwt-secret:latest,DB_PASSWORD=fixbridge-db-password:latest
```

## Turning integrations on

Each is independent, so the AI can be live while payments stay stubbed:

```bash
gcloud run services update fixbridge-backend --region us-east1 \
  --set-env-vars AI_STUB_MODE=false,AI_PROVIDER=openrouter,AI_REASONING=true \
  --update-secrets OPENROUTER_API_KEY=fixbridge-openrouter-key:latest
```

Leave `PAYMENTS_STUB_MODE=true` until Stripe keys exist, or checkout will fail.

## Point the frontend at it

```bash
netlify env:set BACKEND_ORIGIN "https://fixbridge-backend-xxxx.run.app"
netlify deploy --build --prod
```

And allow that origin on the backend:

```bash
gcloud run services update fixbridge-backend --region us-east1 \
  --set-env-vars CORS_ORIGINS=https://<your-site>.netlify.app
```

## Worth knowing

- **Cold starts.** `--min-instances 0` costs nothing idle but adds a few seconds to the first
  request. Set `--min-instances 1` to keep one warm (this is what you pay for).
- **JVM memory.** The container caps its heap from the container limit (`JAVA_OPTS` in the
  Dockerfile). Raising `--memory` without that would let the JVM overshoot and get OOM-killed.
- **Region.** Put Cloud Run in the same region as the database; cross-region hops dominate latency
  for a chatty JDBC connection.
