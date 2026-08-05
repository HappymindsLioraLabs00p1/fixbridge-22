-- FixBridge initial schema (Phase 3 design → executable).
-- Money is stored in integer minor units (cents). Authorization is enforced in the service layer;
-- FKs model ownership. See docs/03-Database.md for the annotated design.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

-- ---------------------------------------------------------------------------
-- Enumerated types
-- ---------------------------------------------------------------------------
CREATE TYPE user_role AS ENUM ('customer','landlord','agent','contractor','admin','partner');
CREATE TYPE job_mode  AS ENUM ('managed','direct','diy');
CREATE TYPE ai_urgency AS ENUM ('low','medium','high','emergency');
CREATE TYPE complexity AS ENUM ('low','medium','high');
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
  ('dispatch_fee','managed_repair','deposit','progress','final_payment','lead_fee','subscription');
CREATE TYPE payment_status AS ENUM
  ('requires_payment','processing','succeeded','failed','refunded','disputed','canceled');
CREATE TYPE transfer_status AS ENUM ('pending','paid','reversed','failed');
CREATE TYPE referral_status AS ENUM
  ('received','customer_contacted','assessment_scheduled','proposal_sent','scheduled','completed','closed');
CREATE TYPE document_status AS ENUM ('pending','valid','expiring','expired','rejected');

-- ---------------------------------------------------------------------------
-- updated_at trigger helper
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Users, roles & properties
-- ---------------------------------------------------------------------------
CREATE TABLE profiles (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email           citext UNIQUE NOT NULL,
  password_hash   text NOT NULL,
  full_name       text,
  phone           text,
  mfa_enabled     boolean NOT NULL DEFAULT false,
  mfa_secret      text,
  email_verified  boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_profiles_updated BEFORE UPDATE ON profiles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_roles (
  user_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  role    user_role NOT NULL,
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
  place_id       text,
  property_type  text,
  access_notes   text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_properties_owner ON properties(owner_id);
CREATE TRIGGER trg_properties_updated BEFORE UPDATE ON properties
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE property_members (
  property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  member_role user_role NOT NULL,
  PRIMARY KEY (property_id, user_id)
);

CREATE TABLE units (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  name        text NOT NULL,
  occupant_id uuid REFERENCES profiles(id)
);

CREATE TABLE property_documents (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
  kind        text NOT NULL,
  storage_key text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Contractors & compliance
-- ---------------------------------------------------------------------------
CREATE TABLE contractors (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id         uuid NOT NULL REFERENCES profiles(id),
  business_name         text NOT NULL,
  contact_email         text,
  contact_phone         text,
  status                contractor_status NOT NULL DEFAULT 'draft',
  min_trip_charge_cents bigint DEFAULT 0,
  travel_radius_miles   int DEFAULT 25,
  languages             text[],
  stripe_account_id     text UNIQUE,
  connect_onboarded     boolean NOT NULL DEFAULT false,
  payouts_enabled       boolean NOT NULL DEFAULT false,
  requirements_due      jsonb,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_contractors_updated BEFORE UPDATE ON contractors
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE contractor_users (
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  user_id       uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (contractor_id, user_id)
);

CREATE TABLE trades (
  id   serial PRIMARY KEY,
  code text UNIQUE NOT NULL,
  name text NOT NULL
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
  kind          text NOT NULL,
  jurisdiction  text,
  number        text,
  storage_key   text,
  status        document_status NOT NULL DEFAULT 'pending',
  expires_on    date,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_contractor_docs_expiry ON contractor_documents(expires_on);

CREATE TABLE contractor_availability (
  contractor_id uuid PRIMARY KEY REFERENCES contractors(id) ON DELETE CASCADE,
  emergency     boolean NOT NULL DEFAULT false,
  hours         jsonb
);

CREATE TABLE contractor_performance (
  contractor_id  uuid PRIMARY KEY REFERENCES contractors(id) ON DELETE CASCADE,
  jobs_completed int NOT NULL DEFAULT 0,
  avg_rating     numeric(3,2),
  response_rate  numeric(5,2),
  callback_rate  numeric(5,2)
);

-- ---------------------------------------------------------------------------
-- Jobs (money-loop core)
-- ---------------------------------------------------------------------------
CREATE TABLE jobs (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id            uuid NOT NULL REFERENCES profiles(id),
  property_id            uuid NOT NULL REFERENCES properties(id),
  mode                   job_mode NOT NULL DEFAULT 'managed',
  status                 job_status NOT NULL DEFAULT 'draft',
  title                  text,
  description            text,
  preferred_time         text,
  assigned_contractor_id uuid REFERENCES contractors(id),
  partner_id             uuid,
  partner_code           text,
  referral_source        text,
  property_purpose       text,
  transaction_stage      text,
  listing_deadline       date,
  closing_deadline       date,
  inspection_report_url  text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_customer ON jobs(customer_id);
CREATE INDEX idx_jobs_status   ON jobs(status);
CREATE TRIGGER trg_jobs_updated BEFORE UPDATE ON jobs
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE job_media (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  storage_key text NOT NULL,
  media_type  text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_assessments (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id                uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  provider              text NOT NULL,
  model                 text NOT NULL,
  category              text,
  summary               text,
  urgency               ai_urgency,
  confidence            numeric(3,2),
  recommended_trade     text,
  professional_required boolean,
  safe_diy_allowed      boolean,
  complexity            complexity,
  labor_hours_min       numeric(5,2),
  labor_hours_max       numeric(5,2),
  raw_json              jsonb NOT NULL,
  admin_override        jsonb,
  created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_invitations (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  contractor_id      uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  status             invitation_status NOT NULL DEFAULT 'invited',
  expected_net_cents bigint,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (job_id, contractor_id)
);

CREATE TABLE bids (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id          uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  contractor_id   uuid NOT NULL REFERENCES contractors(id),
  labor_cents     bigint DEFAULT 0,
  materials_cents bigint DEFAULT 0,
  equipment_cents bigint DEFAULT 0,
  travel_cents    bigint DEFAULT 0,
  permit_cents    bigint DEFAULT 0,
  disposal_cents  bigint DEFAULT 0,
  net_total_cents bigint NOT NULL,
  earliest_start  date,
  duration_days   int,
  warranty        text,
  exclusions      text,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE proposals (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  status             proposal_status NOT NULL DEFAULT 'draft',
  scope              text,
  retail_total_cents bigint NOT NULL,
  deposit_cents      bigint DEFAULT 0,
  timeline           text,
  warranty           text,
  exclusions         text,
  terms              text,
  approved_at        timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE proposal_items (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  proposal_id  uuid NOT NULL REFERENCES proposals(id) ON DELETE CASCADE,
  label        text NOT NULL,
  amount_cents bigint NOT NULL
);

CREATE TABLE appointments (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  scheduled_for timestamptz,
  window_label  text
);

CREATE TABLE change_orders (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  description        text NOT NULL,
  added_net_cents    bigint NOT NULL,
  added_retail_cents bigint NOT NULL,
  added_days         int,
  status             proposal_status NOT NULL DEFAULT 'draft',
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE completion_reports (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id         uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  arrived_at     timestamptz,
  completed_at   timestamptz,
  summary        text,
  materials_used text,
  before_keys    text[],
  after_keys     text[],
  approved_by    uuid REFERENCES profiles(id),
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id     uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  rating     int CHECK (rating BETWEEN 1 AND 5),
  comment    text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_status_history (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id      uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  from_status job_status,
  to_status   job_status NOT NULL,
  actor_id    uuid REFERENCES profiles(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
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
  variable_payment_fee_rate   numeric(6,4),
  fixed_payment_fee_cents     bigint,
  target_gross_margin         numeric(5,4),
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
CREATE TRIGGER trg_job_pricing_updated BEFORE UPDATE ON job_pricing
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE pricing_rules (
  id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  scope                      text NOT NULL DEFAULT 'global',
  trade_code                 text,
  region                     text,
  target_gross_margin        numeric(5,4) NOT NULL DEFAULT 0.2500,
  minimum_gross_profit_cents bigint NOT NULL DEFAULT 7500,
  fixed_platform_cost_cents  bigint NOT NULL DEFAULT 7500,
  risk_reserve_cents         bigint NOT NULL DEFAULT 5000,
  variable_payment_fee_rate  numeric(6,4) NOT NULL DEFAULT 0.0290,
  fixed_payment_fee_cents    bigint NOT NULL DEFAULT 30,
  location_factor            numeric(5,3) NOT NULL DEFAULT 1.0,
  active                     boolean NOT NULL DEFAULT true,
  updated_at                 timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_pricing_rules_updated BEFORE UPDATE ON pricing_rules
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE dispatch_fees (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  service_type           text NOT NULL,
  customer_price_cents   bigint NOT NULL,
  contractor_visit_cents bigint NOT NULL,
  active                 boolean NOT NULL DEFAULT true
);

CREATE TABLE payments (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id                  uuid REFERENCES jobs(id),
  customer_id             uuid REFERENCES profiles(id),
  type                    payment_type NOT NULL,
  status                  payment_status NOT NULL DEFAULT 'requires_payment',
  amount_cents            bigint NOT NULL,
  currency                char(3) NOT NULL DEFAULT 'USD',
  stripe_payment_intent   text,
  stripe_checkout_session text,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_job ON payments(job_id);
CREATE TRIGGER trg_payments_updated BEFORE UPDATE ON payments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE refunds (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id       uuid NOT NULL REFERENCES payments(id),
  amount_cents     bigint NOT NULL,
  reason           text,
  stripe_refund_id text,
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE disputes (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id        uuid NOT NULL REFERENCES payments(id),
  stripe_dispute_id text,
  status            text,
  amount_cents      bigint,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id             uuid NOT NULL REFERENCES jobs(id),
  contractor_id      uuid NOT NULL REFERENCES contractors(id),
  amount_cents       bigint NOT NULL,
  status             transfer_status NOT NULL DEFAULT 'pending',
  stripe_transfer_id text,
  released_by        uuid REFERENCES profiles(id),
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscriptions (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                uuid NOT NULL REFERENCES profiles(id),
  plan_code              text NOT NULL,
  stripe_subscription_id text,
  status                 text,
  current_period_end     timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lead_purchases (
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

-- ---------------------------------------------------------------------------
-- Partners & referrals
-- ---------------------------------------------------------------------------
CREATE TABLE partners (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code       text UNIQUE NOT NULL,
  name       text NOT NULL,
  company    text,
  email      text,
  phone      text,
  type       text,
  active     boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE partner_users (
  partner_id uuid NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (partner_id, user_id)
);

CREATE TABLE partner_referrals (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id  uuid NOT NULL REFERENCES partners(id),
  job_id      uuid REFERENCES jobs(id),
  customer_id uuid REFERENCES profiles(id),
  status      referral_status NOT NULL DEFAULT 'received',
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE partner_referral_events (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  referral_id uuid NOT NULL REFERENCES partner_referrals(id) ON DELETE CASCADE,
  event       text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consent_records (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES profiles(id),
  scope       text NOT NULL,
  version     text NOT NULL,
  granted_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Communication & system
-- ---------------------------------------------------------------------------
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
  channel    text NOT NULL,
  template   text NOT NULL,
  payload    jsonb,
  sent_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE webhook_events (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider     text NOT NULL,
  event_id     text NOT NULL,
  type         text NOT NULL,
  processed_at timestamptz,
  payload      jsonb,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, event_id)
);

CREATE TABLE error_logs (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  area       text NOT NULL,
  message    text NOT NULL,
  context    jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id    uuid REFERENCES profiles(id),
  action      text NOT NULL,
  entity_type text NOT NULL,
  entity_id   uuid,
  before      jsonb,
  after       jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
