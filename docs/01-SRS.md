# FixBridge — Software Requirements Specification (SRS)

**Phase 1 deliverable** · Version 1.0 · Domain source: *FixBridge Product, Revenue, Payments &
Developer Implementation Plan v1.1*

---

## 1. Introduction

### 1.1 Purpose
This SRS defines the requirements for **FixBridge**, an AI-powered property-maintenance and contractor
marketplace platform. It is the authoritative requirements reference for the Architecture, Database,
Backend, Frontend and Integration phases.

### 1.2 Product scope
FixBridge replaces scattered calls, texts, photos, proposals and invoices with **one controlled
workflow from issue to resolution**. It serves homeowners, landlords/property managers, real-estate
agents, contractors and commercial clients. The MVP focus is the **Managed job money-loop**: intake →
AI assessment → server-priced retail estimate → dispatch payment → contractor confidential bid →
retail proposal → customer payment → completion → contractor payout.

### 1.3 Definitions
| Term | Meaning |
|------|---------|
| **Managed job** | FixBridge coordinates the job; a subcontractor performs it under a confidential net work order. FixBridge sets the retail price and keeps the margin. |
| **Direct lead** | Contractor pays a fixed fee to unlock a qualified lead, then handles pricing/payment directly. FixBridge adds no markup. |
| **Net bid / net payout** | Confidential amount the contractor is paid. Never shown to the customer. |
| **Retail price / proposal** | Customer-facing price FixBridge charges. Never shown (as margin) to the contractor. |
| **Pricing engine** | Server-side component that computes retail estimates from admin rules. The AI never sets price. |
| **Dispatch/assessment fee** | Up-front fee the customer pays before a Managed contractor is dispatched. |

### 1.4 Working-name constraint
"FixBridge" is a working name. Brand name, logo, colors, domain, email and legal entity **must be
configuration**, not hard-coded, so the platform can be rebranded in one place.

---

## 2. Product overview

### 2.1 User roles
| Role | Primary need |
|------|--------------|
| **Homeowner / Customer** | Understand and resolve a repair; DIY guidance or a trusted pro; property history. |
| **Landlord / Property Manager** | Manage requests across units, approval + spending limits, recurring maintenance. |
| **Real-Estate Agent** | Prepare a property for listing/closing; convert inspection findings to work orders. |
| **Contractor** | Receive qualified work, submit confidential bids, get paid fast. |
| **Commercial Client** | Multi-site maintenance, NTE limits, SLA tracking, vendor compliance. |
| **Administrator** | Full control: dispatch, pricing, contractor approval, proposals, payouts, refunds, AI override, reporting. |
| **Partner (referral)** | Sends referrals via a unique link; sees limited approved status only. |

### 2.2 Revenue lanes
1. **FixBridge Managed** (primary) — dispatch fee + retail job margin + emergency/PM fees + recurring maintenance.
2. **FixBridge Direct** — fixed lead-unlock fee or contractor software subscription.
3. **DIY / HomeCare** — homeowner monthly/annual subscription + approved affiliate revenue.
4. **Property / Commercial plans** — per-account/location/property fees + managed-job revenue.

All prices are **admin-editable pilot numbers**, never permanent constants.

---

## 3. Functional requirements

Requirements are grouped and identified as `FR-<area>-<n>`. Priority: **M** = MVP (money-loop),
**P2/P3/P4** = later phases.

### 3.1 Accounts & authentication (FR-AUTH)
- **FR-AUTH-1 (M)** Users register and sign in with email + password; JWT access + refresh tokens.
- **FR-AUTH-2 (M)** Every account has one or more roles; role-based access is enforced on every API.
- **FR-AUTH-3 (M)** A user may only read/write properties, jobs and documents they are authorized for.
- **FR-AUTH-4 (P2)** Admin accounts require multi-factor authentication.
- **FR-AUTH-5 (M)** Password reset and email verification flows.

### 3.2 Properties (FR-PROP)
- **FR-PROP-1 (M)** Customer adds properties with validated address + coordinates (Google Places).
- **FR-PROP-2 (P3)** Property profile: type, units, occupants, access/lockbox/parking/pet notes, appliances/systems, preferred contractors, documents, warranties, full repair + spend history.
- **FR-PROP-3 (P3)** Landlord manages multiple properties/units/tenants with per-property budgets and reports.

### 3.3 Issue intake & AI assessment (FR-AI)
- **FR-AI-1 (M)** Customer reports an issue: selects property, describes problem, uploads photos/video, answers simple questions. Wording is **"Report an Issue"**, not "Post a Job".
- **FR-AI-2 (M)** The **AI Assessment Engine** returns a **structured** assessment only: `category, summary, urgency, confidence, recommended_trade, professional_required, safe_diy_allowed, immediate_safety_steps[], visual_findings[], estimated_labor_hours_min/max, complexity, questions_needed[], disclaimer`.
- **FR-AI-3 (M)** Responses use **Structured Outputs** and are schema-validated. On AI failure: show a retry button + professional-request option, log the error, never show raw JSON/HTML/stack traces, never produce an empty plan.
- **FR-AI-4 (M)** Model names are environment configuration (OpenAI + Claude); changeable without a rebuild. Optional second-pass review for high-risk / low-confidence cases.
- **FR-AI-5 (P3)** DIY plans (tools, materials, numbered steps, safety warnings, stop conditions) generated **only** when `safe_diy_allowed = true`.
- **FR-AI-6 (M)** DIY is **blocked** and the user is routed to professional/emergency for: gas/combustion, major electrical/high voltage, active flooding/sewage, fire/smoke/CO, structural, dangerous roof work, suspected hazardous material, or low AI confidence.

### 3.4 Pricing engine (FR-PRICE)
- **FR-PRICE-1 (M)** A **server-side** pricing engine — never the AI model — computes the customer retail **estimate range**.
- **FR-PRICE-2 (M)** Formula: `Retail = (Expected Contractor Net + Fixed Platform Cost + Risk Reserve + Fixed Payment Cost) / (1 − Target Gross Margin − Variable Payment Fee Rate)`.
- **FR-PRICE-3 (M)** For small jobs also enforce a **minimum gross-profit dollar** amount; select the higher of margin-based and minimum-profit price.
- **FR-PRICE-4 (M)** Inputs: expected contractor net, platform operating cost, location factor, urgency/after-hours surcharge, risk reserve, payment fee rate + fixed, target margin, minimum profit, subscription discount, assessment credit. All stored in **admin pricing rules**.
- **FR-PRICE-5 (M)** The customer sees a **range with a disclaimer + confidence**, never a single over-exact number, and never a lower contractor-cost figure that later inflates.
- **FR-PRICE-6 (M)** **Do not show a price** (show "On-site assessment required before pricing") when: low confidence, poor/missing images, structural/electrical/gas/fire/sewage/hazmat risk, large remodel/permit work, commercial engineering, or likely hidden damage. The customer may still pay the dispatch fee.
- **FR-PRICE-7 (P2)** Direct mode shows only a broad market-guidance range with disclaimer, or no estimate.

### 3.5 Managed job workflow (FR-JOB)
- **FR-JOB-1 (M)** Customer selects a preferred service time (labeled **"preferred service time"** until a contractor accepts) and pays the dispatch/assessment fee.
- **FR-JOB-2 (M)** Admin reviews the paid request and invites qualified, compliant contractors.
- **FR-JOB-3 (M)** Before authorization, a contractor sees only general area, trade, urgency, photos, AI scope, preferred time and expected net / bid request — **not** the full address or customer contact.
- **FR-JOB-4 (M)** Contractor may accept, decline, request more info, suggest another time, or submit a **confidential net bid** (labor, materials, equipment, travel/diagnostic, permit, disposal, duration, earliest start, warranty, exclusions, net total).
- **FR-JOB-5 (M)** Admin selects a contractor and generates the **customer retail proposal** (approved scope, retail price, deposit/progress/final schedule, timeline, warranty, exclusions, cancellation/change-order terms).
- **FR-JOB-6 (M)** Customer approves and pays the deposit or full amount.
- **FR-JOB-7 (M)** Contractor completes the job and submits proof: arrival/completion time, before/after photos, work summary, materials, invoice/warranty.
- **FR-JOB-8 (M)** Customer or admin confirms completion; no unresolved change order may remain.
- **FR-JOB-9 (M)** Job status follows the canonical status list (see §7) and is tracked end-to-end.
- **FR-JOB-10 (P3)** Change orders: contractor documents new problem + photos + added net cost/time → admin applies pricing rules → customer approves retail change order before work continues (except authorized immediate safety action).

### 3.6 Payments & payouts (FR-PAY)
- **FR-PAY-1 (M)** Dispatch fee and managed repair payments via Stripe Checkout / PaymentIntent; the charge belongs to the FixBridge platform account.
- **FR-PAY-2 (M)** Record customer retail amount and contractor net amount **separately** on the job.
- **FR-PAY-3 (M)** Contractor onboarding via Stripe-hosted/embedded **Connect**; store connected account id, onboarding status, payout status, requirements due. No custom bank/identity form. A contractor cannot receive jobs or payouts until onboarding is complete.
- **FR-PAY-4 (M)** Funds are **not** transferred to the contractor until completion requirements are approved; admin triggers the **Connect transfer** (separate charges & transfers). Maintain a reserve.
- **FR-PAY-5 (M)** Handle required webhooks: `checkout.session.completed`, `payment_intent.succeeded/…payment_failed`, `invoice.paid/…payment_failed`, `customer.subscription.created/updated/deleted`, `charge.refunded`, `charge.dispute.created`, `account.updated`, `transfer.created/reversed`, `payout.paid/…failed`.
- **FR-PAY-6 (M)** Verify every webhook signature from the raw body; store each event id and process once (idempotency); compute every amount server-side.
- **FR-PAY-7 (P3)** Subscriptions (DIY/HomeCare, Property, Contractor) via Stripe Billing.
- **FR-PAY-8 (P3)** Direct lead fee via Stripe Checkout unlocks contact details on `…completed`.
- **FR-PAY-9 (M)** Refund, dispute and payout-hold workflows with reserve accounting.

### 3.7 Contractor onboarding & compliance (FR-CON)
- **FR-CON-1 (P2)** Contractor profile: business/contact, trades, service ZIPs + travel radius, license (number/jurisdiction/expiry), insurance + expiry, workers' comp, W-9, availability, min trip charge, languages, references/photos.
- **FR-CON-2 (P2)** Approval states: `draft → documents_pending → under_review → approved → suspended/expired/rejected`.
- **FR-CON-3 (P2)** Contractors cannot receive jobs with expired required documents; reminders at 30/14/7 days before expiry; admin can suspend immediately.
- **FR-CON-4 (P3)** AI bid assistant drafts scope/line items/questions but never prices or submits without contractor confirmation.

### 3.8 Admin control (FR-ADMIN)
- **FR-ADMIN-1 (M)** Dispatch console: view urgent/paid requests, invite contractors, assign jobs, monitor no-response.
- **FR-ADMIN-2 (M)** See contractor net cost and customer retail price **separately**; only admin sees both + margin.
- **FR-ADMIN-3 (M)** Approve bids, apply pricing rules, publish proposals, approve change orders.
- **FR-ADMIN-4 (M)** Control refunds, disputes, payouts (approve/hold), subscriptions and pricing rules.
- **FR-ADMIN-5 (P2)** Override AI category/urgency/trade/DIY-eligibility/confidence; suspend contractors; hold payouts.
- **FR-ADMIN-6 (P2)** Reporting: revenue, gross profit, processing cost, conversion, response time, contractor performance.

### 3.9 Partner / referral (FR-REF) — lightweight now
- **FR-REF-1 (P3)** Configurable referral links `…/start?partner=CODE`; the code is saved to the intake; the customer can confirm who referred them; no partner integration required.
- **FR-REF-2 (P3)** Record consent before sharing status; partners see only approved statuses (referral received, contacted, assessment scheduled, proposal sent, scheduled/completed) — never net bid, margin, or financial/credit data.
- **FR-REF-3 (P3)** Property-opportunity intake fields (purpose, transaction stage, deadlines, inspection report URL) stored now; inspection-report converter is a later paid feature.

### 3.10 Notifications (FR-NOTIF)
- **FR-NOTIF-1 (P2)** SMS (Twilio): contractor invitation, proposal/change-order ready, appointment reminder, en-route, emergency, completed, payment/payout status.
- **FR-NOTIF-2 (P2)** Email (Resend): verification, proposal PDF + receipt, subscription confirmation, compliance-expiry reminders, completion/warranty summary, monthly property report.
- **FR-NOTIF-3 (P3)** n8n for **non-critical** alerts/summaries only — never the payment ledger or source of truth.

---

## 4. Non-functional requirements

- **NFR-SEC-1** Role-based authorization on every endpoint; deny by default. A user sees only their authorized property/job data; a contractor can never see the retail price; a customer can never see the net bid.
- **NFR-SEC-2** Secrets only in environment variables; never ship Stripe/OpenAI/Claude/DB keys to the browser. No card or bank numbers stored in our DB (Stripe holds them).
- **NFR-SEC-3** Private object storage; time-limited **signed URLs** for photos/videos/documents.
- **NFR-SEC-4** Audit log for every price, payout, refund, role and status change.
- **NFR-SEC-5** External webhooks verify provider signatures; events are idempotent.
- **NFR-PERF-1** P95 API latency < 500 ms for non-AI/non-payment endpoints; AI and Stripe calls run server-side with timeouts, retries and graceful fallback.
- **NFR-SCAL-1** Stateless backend services behind a load balancer; Redis for cache/session/idempotency/rate-limit; horizontally scalable on Kubernetes.
- **NFR-REL-1** A feature is "done" only when it works in staging, has server-side validation, handles failure, logs important actions, respects permissions, works on mobile, and is tested with realistic data.
- **NFR-UX-1** **Mobile-first**: a customer completes the core flow in under three minutes; normal sans-serif for prices, warnings, buttons and details; role-specific dashboards (no generic dashboard).
- **NFR-OBS-1** Error monitoring (Sentry-style) and product analytics (funnels/conversion) hooks.
- **NFR-ENV-1** Separate **staging** and **production** environments and configuration.

---

## 5. Data visibility matrix

| Data | Customer | Contractor | Admin |
|------|:--------:|:----------:|:-----:|
| Customer retail estimate / proposal | ✅ | ❌ | ✅ |
| Contractor net bid / payout | ❌ | ✅ | ✅ |
| FixBridge margin & internal costs | ❌ | ❌ | ✅ |
| Scope, schedule, completion rules | ✅ | ✅ | ✅ |
| Full address & customer contact | ✅ | Only after authorization | ✅ |

---

## 6. Assumptions, constraints & out-of-scope

- **Constraint:** the money-making Managed loop is built and tested **first**; premium features do not delay it.
- **Constraint:** the AI must not invent prices; all money math is server-side.
- **Out of scope (now):** MLS integration, mortgage/credit/loan features, partner-CRM integrations, nationwide contractor automation, uncontrolled web scraping, closing-based referral commissions.
- **Assumption:** New York legal/accounting/licensing structure is resolved by the business before public Managed jobs; this system keeps the records needed to support that.

---

## 7. Canonical job statuses

```
draft · ai_review_complete · awaiting_service_payment · paid_for_dispatch ·
awaiting_contractor · contractor_invited · contractor_accepted · awaiting_bid ·
bid_received · proposal_sent · awaiting_customer_approval · approved · scheduled ·
contractor_en_route · work_started · change_order_pending · work_completed ·
customer_review_pending · admin_review_pending · payout_pending · paid_out ·
closed · canceled · refunded · disputed
```

---

## 8. Acceptance (MVP money-loop)

The MVP is accepted when, in staging with Stripe test mode, a full Managed job runs end-to-end:
report issue → AI structured assessment → server retail estimate range → pay dispatch fee →
admin invites contractor → contractor confidential net bid → admin retail proposal →
customer approves & pays → completion proof → admin releases Connect payout — with correct
role-based visibility, verified idempotent webhooks, and an audit trail for every money/role/status change.
