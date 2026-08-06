# FixBridge — Deployment (GitHub → Netlify + backend host)

## The one thing to understand first

FixBridge is **two deployables**, and Netlify can only host one of them:

| Piece | What it is | Where it runs |
|-------|-----------|---------------|
| **Frontend** | Next.js 15 / React 19 | ✅ **Netlify** |
| **Backend** | Java 21 / Spring Boot + **PostgreSQL** + **Redis** | ❌ not Netlify — needs a container host |

Netlify runs static assets and short-lived serverless functions. It cannot run a persistent JVM
process or host a database. So the backend goes to a container host (Render, Railway, Fly.io, Koyeb,
or GCP via `infra/terraform`), and Netlify's site proxies `/api/*` to it.

```
browser → https://<you>.netlify.app        (Next.js frontend)
             └─ /api/*  ──proxy──►  https://<backend-host>/api/*   (Spring Boot + Postgres + Redis)
```

The proxy is configured in `frontend/next.config.ts`, so the browser only ever talks to **one
origin** — no CORS problems and no mixed-content errors.

---

## 1. Push to GitHub

```bash
cd "FIX BRIDGE"
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Everything needed is committed; `.gitignore` keeps `node_modules/`, `target/`, `.next/` and all
`.env` files out. **No secrets are in the repo** — only `.env.example` templates.

## 2. Deploy the backend first

You need the backend URL before the frontend build is useful.

**Render (blueprint included):** Render → **New → Blueprint** → pick the repo. It reads
`render.yaml` and creates the API + PostgreSQL + Redis. Then in the dashboard set the values marked
`sync: false`:

- `DB_URL` → `jdbc:postgresql://<internal-db-host>:5432/fixbridge`
- `CORS_ORIGINS` → your Netlify URL, e.g. `https://fixbridge.netlify.app`
- Leave `INTEGRATIONS_STUB_MODE=true` for a keyless demo, or set it `false` and add the
  OpenAI / Claude / Stripe keys.

Flyway creates the schema and seeds the pilot pricing data on first boot. Health check:
`GET /actuator/health`.

> Any host that can build `backend/Dockerfile` works the same way — the file is a plain multi-stage
> Docker build. For GCP (GKE + Cloud SQL + Memorystore) use `infra/terraform` instead.

## 3. Deploy the frontend to Netlify

Netlify → **Add new site → Import from Git** → pick the repo. `netlify.toml` already sets the base
directory (`frontend`), build command, publish directory and the Next.js plugin — **accept the
detected settings**.

Then **Site configuration → Environment variables**:

| Key | Value |
|-----|-------|
| `BACKEND_ORIGIN` | `https://<your-backend-host>` (no trailing slash) |
| `NEXT_PUBLIC_API_URL` | *leave empty* — keeps API calls same-origin |

Redeploy after setting them (env vars are read at build time).

## 4. Close the loop

Set the backend's `CORS_ORIGINS` to the real Netlify URL and restart it. Then verify:

```bash
curl -i https://<you>.netlify.app/api/billing/plans        # 401 = frontend↔backend wired
```

A `401` is success here — it means the request reached the backend and was correctly rejected as
unauthenticated. A `404`/`502` means `BACKEND_ORIGIN` is wrong or the backend is down.

## 5. Demo accounts

Admin cannot self-register (by design). Create the demo logins against the deployed API:

```bash
API=https://<your-backend-host>
for r in customer contractor; do
  curl -s -X POST $API/api/auth/register -H 'Content-Type: application/json' \
    -d "{\"email\":\"$r@demo.local\",\"password\":\"password123\",\"role\":\"$r\"}" > /dev/null
done
curl -s -X POST $API/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.local","password":"password123","role":"customer"}' > /dev/null
```

Then grant the admin role once, via the database console on your host:

```sql
INSERT INTO user_roles (user_id, role)
SELECT id, 'admin' FROM profiles WHERE email = 'admin@demo.local'
ON CONFLICT DO NOTHING;
```

## Custom domain

Netlify → **Domain management** → add your domain (e.g. `fixbridge.com`). Then update:

- backend `CORS_ORIGINS` → the new domain
- `frontend/src/config/brand.ts` (or `NEXT_PUBLIC_BRAND_DOMAIN`) → the new domain
- Stripe success/cancel/Connect URLs, if running live payments

## Gotchas worth knowing

- **Free tiers sleep.** Render's free web service spins down when idle; the first request after
  that takes ~30–60s. Fine for a demo, not for a client pitch — use a paid instance if it must be
  instant.
- **`output: "standalone"` is Docker-only.** It's gated behind `DOCKER_BUILD=1` in
  `next.config.ts` because it breaks Netlify's Next.js runtime.
- **Env vars are build-time.** Changing `BACKEND_ORIGIN` requires a redeploy, not just a save.
- **Stub mode is the safe default.** With `INTEGRATIONS_STUB_MODE=true` the whole flow works with
  no external keys — payments return stub checkout links instead of real Stripe pages.
