# FixBridge — Database Design

**Phase 3 deliverable** · Version 1.0 · PostgreSQL 16 · Migrations via Flyway

This is the source-of-truth schema. DDL below becomes Flyway migrations under
`backend/src/main/resources/db/migration/` in Phase 4. Money is stored in **integer minor units**
(cents) to avoid float error. All tables carry `created_at`/`updated_at`; sensitive tables are
append-audited via `audit_logs`.

---

## 1. Conventions

- **PK:** `uuid` default `gen_random_uuid()` (pgcrypto).
- **Money:** `bigint` cents + `char(3) currency` (default `USD`).
- **Timestamps:** `timestamptz`, default `now()`.
- **Enums:** Postgres `CREATE TYPE … AS ENUM` for closed sets (roles, statuses).
- **Authorization:** enforced in the service layer (row-level checks); FKs model ownership so those
  checks are cheap. Postgres RLS policies can be layered later for defense-in-depth.

---

## 2. Enumerated types

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE user_role AS ENUM
  ('customer','landlord','agent','contractor','admin','partner');

CREATE TYPE job_mode AS ENUM ('managed','direct','diy');

CREATE TYPE ai_urgency  AS ENUM ('low','medium','high','emergency');
CREATE TYPE complexity  AS ENUM ('low','medium','high');

CREATE TYPE job_status AS ENUM (
  'draft','ai_review_complete','awaiting_service_payment','paid_for_dispatch',
  'awaiting_contractor','contractor_invited','contractor_accepted','awaiting_bid',
  'bid_received','proposal_sent','awaiting_customer_approval','approved','scheduled',
  'contractor_en_route','work_started','change_order_pending','work_completed',
  'customer_review_pending','admin_review_pending','payout_pending','paid_out',
  'closed','canceled','refunded','disputed'
);

CREATE TYPE contractor_status AS ENUM
  ('draft','documents_pending','under_review','approved','suspended','expired','rejected');

CREATE TYPE invitation_status AS ENUM
  ('invited','viewed','accepted','declined','info_requested','expired');

CREATE TYPE proposal_status AS ENUM ('draft','sent','approved','declined','expired');

CREATE TYPE payment_type AS ENUM
  ('dispatch_fee','managed_repair','deposit','progress','final','lead_fee','subscription');
CREATE TYPE payment_status AS ENUM
  ('requires_payment','processing','succeeded','failed','refunded','disputed','canceled');

CREATE TYPE transfer_status AS ENUM ('pending','paid','reversed','failed');

CREATE TYPE referral_status AS ENUM
  ('received','customer_contacted','assessment_scheduled','proposal_sent','scheduled','completed','closed');

CREATE TYPE document_status AS ENUM ('pending','valid','expiring','expired','rejected');
```

---

## 3. Users, roles & properties

```sql
CREATE TABLE profiles (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email           citext UNIQUE NOT NULL,
  password_hash   text NOT NULL,
  full_name       text,
  phone           text,
  mfa_enabled     boolean NOT NULL DEFAULT false,
  mfa_secret      text,                       -- TOTP, admin only
  email_verified  boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
  user_id  uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  role     user_role NOT NULL,
  PRIMARY KEY (user_id, role)
);

CREATE TABLE properties (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid NOT NULL REFERENCES profiles(id),
  label          text,
  line1          text NOT NULL,
  line2          text,
  city           text,
  state          text,
  postal_code    text,
  country        char(2) NOT NULL DEFAULT 'US',
  latitude       numeric(9,6),
  longitude      numeric(9,6),
  place_id       text,                        -- Google Places
  property_type  text,                        -- single_family, condo, commercial…
  access_notes   text,                        -- lockbox/parking/pets (private)
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_properties_owner ON properties(owner_id);

CREATE TABLE property_members (         -- landlords/agents/tenants sharing a property
  property_id  uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  user_id      uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  member_role  user_role NOT NULL,
  PRIMARY KEY (property_id, user_id)
);

CREATE TABLE units (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id  uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  name         text NOT NULL,
  occupant_id  uuid REFERENCES profiles(id)
);

CREATE TABLE property_documents (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id  uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  kind         text NOT NULL,                 -- warranty, receipt, inspection…
  storage_key  text NOT NULL,                 -- GCS object key (private)
  created_at   timestamptz NOT NULL DEFAULT now()
);
```

---

## 4. Contractors & compliance

```sql
CREATE TABLE contractors (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id        uuid NOT NULL REFERENCES profiles(id),
  business_name        text NOT NULL,
  contact_email        text,
  contact_phone        text,
  status               contractor_status NOT NULL DEFAULT 'draft',
  min_trip_charge_cents bigint DEFAULT 0,
  travel_radius_miles  int DEFAULT 25,
  languages            text[],
  -- Stripe Connect
  stripe_account_id    text UNIQUE,
  connect_onboarded    boolean NOT NULL DEFAULT false,
  payouts_enabled      boolean NOT NULL DEFAULT false,
  requirements_due     jsonb,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE contractor_users (        -- team members under a contractor account
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  user_id       uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (contractor_id, user_id)
);

CREATE TABLE trades (
  id    serial PRIMARY KEY,
  code  text UNIQUE NOT NULL,          -- plumbing, electrical, handyman…
  name  text NOT NULL
);

CREATE TABLE contractor_trades (
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  trade_id      int  NOT NULL REFERENCES trades(id),
  PRIMARY KEY (contractor_id, trade_id)
);

CREATE TABLE service_areas (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  postal_code   text NOT NULL
);
CREATE INDEX idx_service_areas_zip ON service_areas(postal_code);

CREATE TABLE contractor_documents (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  kind          text NOT NULL,        -- license, insurance, workers_comp, w9
  jurisdiction  text,
  number        text,
  storage_key   text,                 -- GCS private object
  status        document_status NOT NULL DEFAULT 'pending',
  expires_on    date,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_contractor_docs_expiry ON contractor_documents(expires_on);

CREATE TABLE contractor_availability (
  contractor_id uuid PRIMARY KEY REFERENCES contractors(id) ON DELETE CASCADE,
  emergency     boolean NOT NULL DEFAULT false,
  hours         jsonb                 -- weekly working hours
);

CREATE TABLE contractor_performance (
  contractor_id uuid PRIMARY KEY REFERENCES contractors(id) ON DELETE CASCADE,
  jobs_completed int NOT NULL DEFAULT 0,
  avg_rating     numeric(3,2),
  response_rate  numeric(5,2),
  callback_rate  numeric(5,2)
);
```

---

## 5. Jobs (the money-loop core)

```sql
CREATE TABLE jobs (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id        uuid NOT NULL REFERENCES profiles(id),
  property_id        uuid NOT NULL REFERENCES properties(id),
  mode               job_mode NOT NULL DEFAULT 'managed',
  status             job_status NOT NULL DEFAULT 'draft',
  title              text,
  description        text,
  preferred_time     text,                 -- "preferred service time" until accepted
  assigned_contractor_id uuid REFERENCES contractors(id),
  -- referral / property-opportunity (lightweight now)
  partner_id         uuid,
  partner_code       text,
  referral_source    text,
  property_purpose   text,                 -- current_homeowner, fix_and_flip…
  transaction_stage  text,
  listing_deadline   date,
  closing_deadline   date,
  inspection_report_url text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_customer ON jobs(customer_id);
CREATE INDEX idx_jobs_status   ON jobs(status);

CREATE TABLE job_media (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  storage_key text NOT NULL,               -- GCS private object; served via signed URL
  media_type  text NOT NULL,               -- image/video
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_assessments (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  provider           text NOT NULL,        -- openai | claude
  model              text NOT NULL,        -- from env, stored for audit
  category           text,
  summary            text,
  urgency            ai_urgency,
  confidence         numeric(3,2),
  recommended_trade  text,
  professional_required boolean,
  safe_diy_allowed   boolean,
  complexity         complexity,
  labor_hours_min    numeric(5,2),
  labor_hours_max    numeric(5,2),
  raw_json           jsonb NOT NULL,        -- full validated structured output
  admin_override     jsonb,                 -- admin corrections
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_invitations (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  status        invitation_status NOT NULL DEFAULT 'invited',
  expected_net_cents bigint,               -- shown to contractor if fixed
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (job_id, contractor_id)
);

CREATE TABLE bids (                        -- CONFIDENTIAL contractor net bid
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id         uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  contractor_id  uuid NOT NULL REFERENCES contractors(id),
  labor_cents        bigint DEFAULT 0,
  materials_cents    bigint DEFAULT 0,
  equipment_cents    bigint DEFAULT 0,
  travel_cents       bigint DEFAULT 0,
  permit_cents       bigint DEFAULT 0,
  disposal_cents     bigint DEFAULT 0,
  net_total_cents    bigint NOT NULL,      -- never exposed to customer
  earliest_start     date,
  duration_days      int,
  warranty           text,
  exclusions         text,
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE proposals (                   -- customer-facing RETAIL proposal
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id                uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  status                proposal_status NOT NULL DEFAULT 'draft',
  scope                 text,
  retail_total_cents    bigint NOT NULL,   -- never exposed (as margin) to contractor
  deposit_cents         bigint DEFAULT 0,
  timeline              text,
  warranty              text,
  exclusions            text,
  terms                 text,
  approved_at           timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE proposal_items (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  proposal_id  uuid NOT NULL REFERENCES proposals(id) ON DELETE CASCADE,
  label        text NOT NULL,
  amount_cents bigint NOT NULL
);

CREATE TABLE appointments (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  scheduled_for timestamptz,
  window_label  text
);

CREATE TABLE change_orders (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id          uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  description     text NOT NULL,
  added_net_cents bigint NOT NULL,         -- confidential
  added_retail_cents bigint NOT NULL,      -- customer-facing
  added_days      int,
  status          proposal_status NOT NULL DEFAULT 'draft',
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE completion_reports (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  arrived_at    timestamptz,
  completed_at  timestamptz,
  summary       text,
  materials_used text,
  before_keys   text[],                    -- GCS keys
  after_keys    text[],
  approved_by   uuid REFERENCES profiles(id),
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  rating      int CHECK (rating BETWEEN 1 AND 5),
  comment     text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_status_history (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  from_status job_status,
  to_status   job_status NOT NULL,
  actor_id    uuid REFERENCES profiles(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
```

---

## 6. Money

```sql
-- Per-job pricing ledger — Appendix A fields, all server-computed
CREATE TABLE job_pricing (
  job_id                      uuid PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
  ai_category                 text,
  ai_urgency                  ai_urgency,
  ai_confidence               numeric(3,2),
  est_contractor_net_low      bigint,
  est_contractor_net_high     bigint,
  contractor_final_net_cents  bigint,
  fixed_platform_cost_cents   bigint,
  risk_reserve_cents          bigint,
  variable_payment_fee_rate   numeric(6,4),   -- e.g. 0.0290
  fixed_payment_fee_cents     bigint,
  target_gross_margin         numeric(5,4),   -- e.g. 0.2500
  minimum_gross_profit_cents  bigint,
  location_factor             numeric(5,3) DEFAULT 1.0,
  urgency_surcharge_cents     bigint DEFAULT 0,
  after_hours_surcharge_cents bigint DEFAULT 0,
  subscription_discount_cents bigint DEFAULT 0,
  assessment_credit_cents     bigint DEFAULT 0,
  customer_retail_low         bigint,
  customer_retail_high        bigint,
  customer_final_retail_cents bigint,
  processing_cost_cents       bigint,
  platform_gross_profit_cents bigint,
  updated_at                  timestamptz NOT NULL DEFAULT now()
);

-- Admin-editable pricing rules (defaults + per-trade/region overrides)
CREATE TABLE pricing_rules (
  id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  scope                       text NOT NULL DEFAULT 'global',   -- global|trade|region
  trade_code                  text,
  region                      text,
  target_gross_margin         numeric(5,4) NOT NULL DEFAULT 0.2500,
  minimum_gross_profit_cents  bigint NOT NULL DEFAULT 7500,
  fixed_platform_cost_cents   bigint NOT NULL DEFAULT 7500,
  risk_reserve_cents          bigint NOT NULL DEFAULT 5000,
  variable_payment_fee_rate   numeric(6,4) NOT NULL DEFAULT 0.0290,
  fixed_payment_fee_cents     bigint NOT NULL DEFAULT 30,
  location_factor             numeric(5,3) NOT NULL DEFAULT 1.0,
  active                      boolean NOT NULL DEFAULT true,
  updated_at                  timestamptz NOT NULL DEFAULT now()
);

-- Admin-editable dispatch fee catalog (pilot prices, not hard-coded)
CREATE TABLE dispatch_fees (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  service_type           text NOT NULL,     -- weekday, same_day, evening_weekend, commercial…
  customer_price_cents   bigint NOT NULL,
  contractor_visit_cents bigint NOT NULL,
  active                 boolean NOT NULL DEFAULT true
);

CREATE TABLE payments (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid REFERENCES jobs(id),
  customer_id        uuid REFERENCES profiles(id),
  type               payment_type NOT NULL,
  status             payment_status NOT NULL DEFAULT 'requires_payment',
  amount_cents       bigint NOT NULL,
  currency           char(3) NOT NULL DEFAULT 'USD',
  stripe_payment_intent text,
  stripe_checkout_session text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_job ON payments(job_id);

CREATE TABLE refunds (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id    uuid NOT NULL REFERENCES payments(id),
  amount_cents  bigint NOT NULL,
  reason        text,
  stripe_refund_id text,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE disputes (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id    uuid NOT NULL REFERENCES payments(id),
  stripe_dispute_id text,
  status        text,
  amount_cents  bigint,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transfers (             -- Stripe Connect payout to contractor
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id         uuid NOT NULL REFERENCES jobs(id),
  contractor_id  uuid NOT NULL REFERENCES contractors(id),
  amount_cents   bigint NOT NULL,
  status         transfer_status NOT NULL DEFAULT 'pending',
  stripe_transfer_id text,
  released_by    uuid REFERENCES profiles(id),
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscriptions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL REFERENCES profiles(id),
  plan_code     text NOT NULL,        -- diy_plus, homecare_plus, property_pro, contractor_pro…
  stripe_subscription_id text,
  status        text,
  current_period_end timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lead_purchases (        -- Direct mode
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        uuid NOT NULL REFERENCES jobs(id),
  contractor_id uuid NOT NULL REFERENCES contractors(id),
  fee_cents     bigint NOT NULL,
  unlocked_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE affiliate_clicks (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid REFERENCES profiles(id),
  product_ref text,
  source      text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
```

---

## 7. Partners & referrals (lightweight)

```sql
CREATE TABLE partners (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code           text UNIQUE NOT NULL,        -- used in /start?partner=CODE
  name           text NOT NULL,
  company        text,
  email          text,
  phone          text,
  type           text,                        -- agent, lender_contact, investor…
  active         boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE partner_users (
  partner_id uuid NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (partner_id, user_id)
);

CREATE TABLE partner_referrals (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id    uuid NOT NULL REFERENCES partners(id),
  job_id        uuid REFERENCES jobs(id),
  customer_id   uuid REFERENCES profiles(id),
  status        referral_status NOT NULL DEFAULT 'received',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE partner_referral_events (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  referral_id   uuid NOT NULL REFERENCES partner_referrals(id) ON DELETE CASCADE,
  event         text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consent_records (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id    uuid NOT NULL REFERENCES profiles(id),
  scope          text NOT NULL,               -- partner_status_sharing
  version        text NOT NULL,
  granted_at     timestamptz NOT NULL DEFAULT now()
);
```

> **Privacy constraint:** never store SSNs, DOBs, credit reports, mortgage/loan applications, tax
> returns, pay stubs or bank statements. Only property-repair/referral data lives here.

---

## 8. Communication & system

```sql
CREATE TABLE conversations (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id     uuid REFERENCES jobs(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE messages (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id       uuid REFERENCES profiles(id),
  body            text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES profiles(id),
  channel    text NOT NULL,          -- sms | email | in_app
  template   text NOT NULL,
  payload    jsonb,
  sent_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Stripe/provider webhook idempotency (process each event exactly once)
CREATE TABLE webhook_events (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider      text NOT NULL,        -- stripe
  event_id      text NOT NULL,
  type          text NOT NULL,
  processed_at  timestamptz,
  payload       jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, event_id)
);

CREATE TABLE error_logs (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  area       text NOT NULL,          -- ai, payment, webhook…
  message    text NOT NULL,
  context    jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Every price/payout/refund/role/status change
CREATE TABLE audit_logs (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id     uuid REFERENCES profiles(id),
  action       text NOT NULL,        -- proposal.publish, payout.release, role.grant…
  entity_type  text NOT NULL,
  entity_id    uuid,
  before       jsonb,
  after        jsonb,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
```

---

## 9. Table-group summary

| Group | Tables |
|-------|--------|
| Users / properties | `profiles`, `user_roles`, `properties`, `property_members`, `units`, `property_documents` |
| Contractors | `contractors`, `contractor_users`, `trades`, `contractor_trades`, `service_areas`, `contractor_documents`, `contractor_availability`, `contractor_performance` |
| Jobs | `jobs`, `job_media`, `ai_assessments`, `job_invitations`, `bids`, `proposals`, `proposal_items`, `appointments`, `change_orders`, `completion_reports`, `reviews`, `job_status_history` |
| Money | `job_pricing`, `pricing_rules`, `dispatch_fees`, `payments`, `refunds`, `disputes`, `transfers`, `subscriptions`, `lead_purchases`, `affiliate_clicks` |
| Partners | `partners`, `partner_users`, `partner_referrals`, `partner_referral_events`, `consent_records` |
| Comms / system | `conversations`, `messages`, `notifications`, `webhook_events`, `error_logs`, `audit_logs` |

---

## 10. Confidentiality enforcement (DB → API)

- `bids.net_total_cents` and cost breakdown → **contractor + admin only**.
- `proposals.retail_total_cents` and `job_pricing.platform_gross_profit_cents` → **customer sees retail; contractor sees neither; admin sees all**.
- These are enforced by service-layer DTO projections (a customer query never selects net columns; a
  contractor query never selects retail/margin columns). Postgres RLS policies may be added as a second
  layer before production.
