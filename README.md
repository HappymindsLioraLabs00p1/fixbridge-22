# FixBridge

> **FixBridge is a working product name.** The name, logo, colors, domain, support email and legal
> entity are **never hard-coded** — they live in a single branding configuration
> (`frontend/src/config/brand.ts` and backend `brand.*` properties) so the platform can be renamed
> in one place.

An AI-powered **property care & service coordination platform**: homeowners, landlords, agents,
property managers and commercial clients report maintenance issues, get an AI-assisted assessment,
pay a dispatch fee, and a verified contractor is dispatched under a **confidential net work order**.
FixBridge builds the customer-facing retail proposal (with its margin hidden), the customer approves
and pays, the work is completed, and the contractor is paid out via **Stripe Connect**.

The number-one build objective is **one reliable money-making loop**:

```
report issue → AI assessment → server-side retail estimate → pay dispatch fee →
admin assigns verified contractor → contractor submits confidential net bid →
FixBridge creates retail proposal → customer approves & pays →
work completed + proof → admin releases contractor payout
```

## Tech stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 3, Spring Security + JWT, Spring Data JPA, PostgreSQL, Redis, Maven |
| **Frontend** | Next.js 15, React 19, TypeScript, Tailwind CSS, ShadCN UI, TanStack React Query, Zustand |
| **AI** | OpenAI Responses API (vision + structured outputs) and Anthropic Claude API — model names configurable, never hard-coded |
| **Payments** | Stripe Checkout / Billing / Connect (separate charges & transfers) |
| **Infrastructure** | Docker, Kubernetes, Terraform, Google Cloud Platform (GCP) |

## Repository layout

```
FIX BRIDGE/
├── docs/              # Phase deliverables (SRS, Architecture, Database, API spec)
│   ├── 01-SRS.md
│   ├── 02-Architecture.md
│   └── 03-Database.md
├── backend/           # Spring Boot 3 service (Phase 4)
├── frontend/          # Next.js 15 app (Phase 5)
└── infra/             # Docker, Kubernetes, Terraform for GCP
    ├── docker/
    ├── k8s/
    └── terraform/
```

## Build phases

1. **SRS** — [`docs/01-SRS.md`](docs/01-SRS.md) ✅
2. **Architecture** — [`docs/02-Architecture.md`](docs/02-Architecture.md) ✅
3. **Database** — [`docs/03-Database.md`](docs/03-Database.md) ✅
4. **Backend APIs** — Spring Boot money-loop service, compiles on JDK 21 ✅
5. **Frontend UI** — Next.js app (Customer/Contractor/Admin), builds clean ✅
6. **API Integration** — full managed-job loop verified end-to-end on PostgreSQL 16 ✅

## Running locally

**With Docker (one command):**

```bash
docker compose -f infra/docker/docker-compose.yml up --build
```

Backend on `http://localhost:8080` (Swagger UI at `/docs`), frontend on `http://localhost:3000`.
Runs in stub mode — no Stripe/OpenAI/Claude keys needed.

**Without Docker** (needs JDK 21, Node 20+, Postgres, Redis):

```bash
# backend
cd backend && ./mvnw -DskipTests package   # or: mvn -DskipTests package
DB_URL=jdbc:postgresql://localhost:5432/fixbridge DB_USER=fixbridge DB_PASSWORD=fixbridge \
  java -jar target/fixbridge-backend-0.1.0.jar

# frontend (separate terminal)
cd frontend && npm install && npm run dev
```

The managed money-loop has been run end-to-end (report issue → AI assessment → server retail
estimate → dispatch fee → idempotent webhook → admin invite → confidential net bid → retail proposal
with hidden margin → customer approve & pay → completion → Connect payout), confirming role-based
confidentiality at every step.

## Continuous integration

`.github/workflows/ci.yml` runs on every push/PR:

- **Backend** — `mvn verify` (unit tests + the Testcontainers end-to-end managed-job test; the latter
  runs on CI because GitHub runners have Docker, and self-skips locally without it).
- **Frontend** — `npm ci` + `npm run build` (lint + type-check + build).
- **Infra** — `terraform fmt -check` + `terraform validate`.

## Non-negotiable rules (from the product spec)

- **The AI never invents the price.** A separate **server-side pricing engine** computes the retail
  estimate from admin-configurable rules. The AI only classifies the problem, trade, urgency and risk.
- **Pricing confidentiality.** The customer sees the retail price only; the contractor sees the net
  payout only; **only the admin sees both** and the margin.
- **All money math is server-side.** Never trust prices/amounts from the browser. Every Stripe webhook
  signature is verified from the raw body and each event is processed exactly once (idempotency).
- **Security.** Role-based access on every endpoint, private object storage with signed URLs, audit
  logs for every price/payout/refund/role change, secrets only in environment variables.
