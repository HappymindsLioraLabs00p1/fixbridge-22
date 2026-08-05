# FixBridge — Architecture

**Phase 2 deliverable** · Version 1.0

---

## 1. Architectural goals

- **Confidentiality by construction** — margin/net-bid separation enforced in the service + authorization layer, not the UI.
- **Server-authoritative money** — pricing, Stripe amounts and payouts computed and verified server-side.
- **Configurable everything** — brand, model names, pricing rules, fees are config/data, not code constants.
- **Stateless & horizontally scalable** — services behind a load balancer, Redis for shared state, deployable on GKE.
- **Clear module boundaries** so Phase 4/5/6 can be built and tested independently.

---

## 2. System context

```
                         ┌──────────────────────────────────────────┐
                         │                Clients                   │
                         │  Web (Next.js 15, mobile-first, PWA)     │
                         └───────────────┬──────────────────────────┘
                                         │ HTTPS (JWT bearer)
                                         ▼
                         ┌──────────────────────────────────────────┐
                         │        API Gateway / Ingress (GKE)       │
                         └───────────────┬──────────────────────────┘
                                         │
                         ┌───────────────▼──────────────────────────┐
                         │      Spring Boot 3 Backend (stateless)    │
                         │  Auth · Properties · Jobs · AI · Pricing  │
                         │  Payments · Contractors · Admin · Webhooks│
                         └──┬─────────┬─────────┬──────────┬─────────┘
                            │         │         │          │
                 ┌──────────▼──┐ ┌────▼────┐ ┌──▼──────┐ ┌─▼───────────────┐
                 │ PostgreSQL  │ │  Redis  │ │  GCS    │ │ External APIs   │
                 │ (Cloud SQL) │ │(Memstore)│ │ (files)│ │ OpenAI · Claude │
                 └─────────────┘ └─────────┘ └─────────┘ │ Stripe · Twilio │
                                                          │ Resend · Google │
                                                          │ Places/Maps     │
                                                          └─────────────────┘
```

External providers are reached **only** from the backend. The browser never holds Stripe/OpenAI/
Claude/DB secrets.

---

## 3. Backend architecture (Spring Boot 3, Java 21)

### 3.1 Layering
```
controller → service → repository → entity → PostgreSQL
     │           │
     │           └── integration clients (Stripe, OpenAI, Claude, Twilio, Resend, Places)
     └── DTOs + Bean Validation; role checks via @PreAuthorize
```
- **Controllers** — thin; validate DTOs, enforce role, map to services. No business logic.
- **Services** — all business rules and transactions (`@Transactional`). The **only** place money and visibility rules live.
- **Repositories** — Spring Data JPA; custom queries where needed.
- **Integration clients** — wrap each external API behind an interface so it can be mocked/stubbed (Phase-4 frontend-first uses stub implementations).

### 3.2 Module (package) boundaries — `com.fixbridge`
| Module | Responsibility |
|--------|----------------|
| `config` | Brand config, security config, Redis, CORS, OpenAPI, Jackson, properties binding. |
| `auth` | Registration, login, JWT issue/refresh, password reset, MFA (admin), `UserDetails`. |
| `user` | Profiles, roles, memberships. |
| `property` | Properties, units, members, documents; address validation client. |
| `job` | Jobs, media, invitations, status machine, appointments, change orders, completion, reviews, status history. |
| `ai` | Assessment orchestration; OpenAI + Claude clients; structured-output schema validation; safety rules; DIY plans. |
| `pricing` | Pricing engine, pricing rules, retail estimate calculation, "no-price" gating. |
| `payment` | Stripe Checkout/PaymentIntent, Connect onboarding + transfers, Billing/subscriptions, refunds/disputes, reserve, **webhook handler (idempotent)**. |
| `contractor` | Contractor profiles, trades, service areas, documents/compliance, availability, bids, performance. |
| `proposal` | Retail proposals, line items, approvals. |
| `admin` | Dispatch, AI override, pricing-rule management, reporting. |
| `partner` | Referral links, referrals, referral events, consent records. |
| `notification` | Twilio + Resend clients, templates, n8n outbound (non-critical). |
| `common` | Errors, `ApiError` responses, audit logging, idempotency, base entities, enums (statuses, roles). |

### 3.3 Security
- **Spring Security** stateless filter chain; **JWT** bearer (short-lived access + refresh token).
- **`@PreAuthorize`** method security for role gates; **row-level authorization** in services (a user can only touch their authorized property/job — the DB-RLS equivalent).
- Refresh-token rotation + revocation list in **Redis**; login rate-limiting in Redis.
- Admin MFA (TOTP) required before payout/refund/pricing-rule mutations.
- CORS locked to the frontend origin per environment.

### 3.4 The two-engine AI + pricing separation (critical)
```
media + answers ──▶ AI Assessment Engine ──▶ structured assessment (category, urgency,
                     (OpenAI / Claude,          trade, confidence, safe_diy, labor hours…)
                      schema-validated)                  │
                                                         ▼
                                              Pricing Engine (server)
                                              applies admin rules + formula
                                                         │
                                                         ▼
                                       customer retail estimate RANGE (+ disclaimer,
                                       confidence) — or "on-site assessment required"
```
- **AI provider abstraction:** `AiAssessmentClient` interface with `OpenAiResponsesClient` and
  `ClaudeClient` implementations; provider + model name are configuration. Optional second-pass review
  for high-risk/low-confidence uses the other provider.
- **Schema validation:** the AI must return JSON matching the assessment schema; invalid → retry →
  fallback to "professional request" (never raw error, never empty plan). Failures logged to `error_logs`.
- **Pricing engine never calls the model for price.** It reads `pricing_rules` + assessment signals only.

### 3.5 Payments
- **Separate charges & transfers**: customer is charged on the platform account; contractor is paid
  by an explicit **Connect transfer** only after completion approval.
- **Webhook controller** reads the **raw body**, verifies the Stripe signature, deduplicates on
  `event.id` via `webhook_events` (unique) + Redis short-lock, then dispatches to handlers inside a
  transaction. Amounts are recomputed server-side; frontend amounts are never trusted.
- A **reserve** is retained; payouts can be held, partially transferred, or reversed by admin.

### 3.6 Caching & jobs (Redis)
- Cache: pricing rules, brand config, contractor eligibility lookups.
- Idempotency keys and webhook de-dup locks.
- Rate limiting and JWT refresh/blocklist.
- Async work (notifications, AI second-pass, reminders) via Spring `@Async` / scheduled tasks;
  n8n handles only non-critical outbound.

---

## 4. Frontend architecture (Next.js 15, React 19)

- **App Router** with route groups per role: `(customer)`, `(contractor)`, `(admin)`, plus `(auth)`
  and a public `start` intake (referral links). **Role-specific dashboards** — no generic dashboard.
- **Server Components** for data-display pages; **Client Components** for interactive flows (intake,
  bid, proposal approval, payment redirects).
- **State:** **TanStack React Query** for all server state (queries/mutations, cache, retries);
  **Zustand** for local UI/session state (current role view, intake wizard progress, toasts).
- **UI:** **Tailwind CSS** + **ShadCN UI** components; normal sans-serif for prices/warnings/buttons.
- **Branding:** single `src/config/brand.ts` (name, logo, palette, support email, domain) consumed via
  a theme provider + CSS variables — the only place brand identity lives.
- **API client:** typed fetch wrapper attaching the JWT, refreshing on 401, surfacing `ApiError` shapes;
  never displays raw server errors.
- **Money confidentiality on the client is cosmetic only** — the server is the source of truth for what
  each role may fetch; the UI simply never requests forbidden fields.

---

## 5. Data & storage

- **PostgreSQL (Cloud SQL)** — single source of truth. Schema in [`03-Database.md`](03-Database.md);
  migrations via **Flyway**.
- **Redis (Memorystore)** — cache/session/idempotency/rate-limit (not a system of record).
- **Google Cloud Storage** — private buckets for photos/video/documents; access via time-limited
  **signed URLs** issued by the backend.

---

## 6. Infrastructure (Docker · Kubernetes · Terraform · GCP)

| Concern | Choice |
|---------|--------|
| Containers | Multi-stage Dockerfiles: backend (JRE 21 slim), frontend (Next.js standalone). |
| Local dev | `docker-compose` (Postgres + Redis + backend + frontend + stub externals). |
| Orchestration | **GKE**; backend + frontend as Deployments, HPA on CPU/latency; Ingress + managed TLS. |
| Data services | **Cloud SQL** (PostgreSQL), **Memorystore** (Redis), **GCS** buckets. |
| Secrets | **GCP Secret Manager**, mounted as env vars; nothing in images or the browser. |
| IaC | **Terraform** for VPC, GKE, Cloud SQL, Memorystore, GCS, Secret Manager, IAM, Artifact Registry. |
| CI/CD | Build + test → push to Artifact Registry → deploy to **staging**, then **production** (separate projects/namespaces). |
| Observability | Sentry-style error monitoring + product analytics; structured logs to Cloud Logging. |

---

## 7. Environments & configuration

- **staging** and **production** are fully separate (DB, Redis, buckets, Stripe keys, GCP project).
- All of the following are environment/config, never hard-coded: brand identity, AI provider + model
  names, Stripe product/price IDs, pricing-rule defaults, fee percentages, external API keys, CORS origins.

---

## 8. Key request flows

**Managed job (happy path):**
```
Customer POST /jobs (property, description, media)
  → AI service returns structured assessment (status: ai_review_complete)
  → Pricing service returns retail range (or "assessment required")
Customer POST /payments/dispatch  → Stripe Checkout → webhook checkout.session.completed
  → job status: paid_for_dispatch
Admin POST /jobs/{id}/invitations → contractors invited (limited view)
Contractor POST /jobs/{id}/bids (confidential net)  → status: bid_received
Admin POST /jobs/{id}/proposal (retail price from pricing rules) → proposal_sent
Customer POST /proposals/{id}/approve + pay → webhook payment_intent.succeeded → approved/scheduled
Contractor POST /jobs/{id}/completion (proof) → work_completed
Admin POST /jobs/{id}/payout → Stripe Connect transfer → payout.paid → paid_out
```
Every money/role/status transition writes an **audit log**; every Stripe event is verified + processed once.
