-- Guided repair: conversations, plans, steps and photo verifications (approved change).
--
-- Java owns this state, as it owns all business state; the Python service stays stateless and is
-- handed whatever history it needs on each call.

CREATE TYPE conversation_status AS ENUM (
  'NEED_MORE_INFORMATION', 'NEED_IMAGE', 'REPAIR_PLAN_READY',
  'PROFESSIONAL_REQUIRED', 'EMERGENCY'
);

CREATE TYPE safety_level AS ENUM (
  'SAFE_DIY', 'PROFESSIONAL_REQUIRED', 'EMERGENCY', 'INSUFFICIENT_INFORMATION'
);

CREATE TYPE step_state AS ENUM ('pending', 'in_progress', 'completed', 'verified', 'failed');

CREATE TYPE verification_result AS ENUM (
  'STEP_COMPLETED', 'STEP_NOT_COMPLETED', 'UNCERTAIN', 'ESCALATE'
);

CREATE TABLE repair_conversations (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id  uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  -- Set only if the conversation escalates into a real dispatched job, so the guided-repair flow
  -- and the managed-job flow stay linked without being coupled.
  job_id       uuid REFERENCES jobs(id) ON DELETE SET NULL,
  category     text,
  problem      text,
  status       conversation_status NOT NULL DEFAULT 'NEED_MORE_INFORMATION',
  safety_level safety_level,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE conversation_messages (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES repair_conversations(id) ON DELETE CASCADE,
  role            text NOT NULL CHECK (role IN ('customer', 'assistant')),
  body            text,
  -- Storage keys, never public URLs: a stored URL would outlive its signature.
  image_keys      text[] NOT NULL DEFAULT '{}',
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repair_plans (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id   uuid NOT NULL REFERENCES repair_conversations(id) ON DELETE CASCADE,
  problem           text NOT NULL,
  category          text,
  safety_level      safety_level NOT NULL,
  estimated_minutes int,
  stop_conditions   text[] NOT NULL DEFAULT '{}',
  -- The whole validated plan, so what the customer was shown is recoverable even if the
  -- normalised columns later change shape.
  raw_json          jsonb NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repair_steps (
  id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_id                     uuid NOT NULL REFERENCES repair_plans(id) ON DELETE CASCADE,
  step_number                 int NOT NULL,
  instruction                 text NOT NULL,
  why                         text,
  tools                       text[] NOT NULL DEFAULT '{}',
  parts                       text[] NOT NULL DEFAULT '{}',
  warnings                    text[] NOT NULL DEFAULT '{}',
  expected_result             text,
  requires_image_verification boolean NOT NULL DEFAULT false,
  state                       step_state NOT NULL DEFAULT 'pending',
  created_at                  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (plan_id, step_number)
);

CREATE TABLE repair_step_verifications (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  step_id    uuid NOT NULL REFERENCES repair_steps(id) ON DELETE CASCADE,
  result     verification_result NOT NULL,
  confidence numeric(3,2),
  reason     text,
  image_keys text[] NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);

-- A customer opening the app lists their own conversations, newest first.
CREATE INDEX idx_repair_conversations_customer ON repair_conversations(customer_id, created_at DESC);
CREATE INDEX idx_repair_conversations_job ON repair_conversations(job_id) WHERE job_id IS NOT NULL;
-- Replaying a conversation reads its messages in order.
CREATE INDEX idx_conversation_messages_conv ON conversation_messages(conversation_id, created_at);
CREATE INDEX idx_repair_plans_conv ON repair_plans(conversation_id);
CREATE INDEX idx_repair_steps_plan ON repair_steps(plan_id, step_number);
CREATE INDEX idx_step_verifications_step ON repair_step_verifications(step_id, created_at DESC);
