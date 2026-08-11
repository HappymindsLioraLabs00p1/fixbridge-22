# FixBuddy — Integration Plan

**Status:** proposal. No code written.
**Date:** 11 August 2026

This responds to two briefs: the FixBuddy animated assistant, and the mobile UI/UX build.
Both are written for a React Native/Expo application. That application does not exist in this
repository. This plan states what is actually here, what genuinely blocks the briefs, and what I
would build instead.

---

## 1. Findings

### 1.1 There is no React Native/Expo project

Verified by absence of `app.json`, `app.config.*`, `metro.config.*`, `eas.json`, and any
`react-native` or `expo` dependency in any `package.json` in the repo.

The only frontend is `frontend/` — **Next.js 15 + React 19 + TypeScript + Tailwind v4**, installed
on phones as a PWA. There is therefore no existing React Navigation stack, Expo camera module, or
native theme to reuse. Creating one means building a new application, which both briefs forbid in
their opening lines.

### 1.2 Two other premises don't hold

- **CrewAI is not used.** The AI service is a custom FastAPI implementation (`fixbridge-ai-service/`)
  with a hand-written tool registry and state machine. There is no CrewAI dependency.
- **No Figma designs have been supplied.** The mobile brief refers to "the supplied Figma designs";
  none were provided.

### 1.3 A mascot already exists

`frontend/src/components/mascot-assistant.tsx` (241 lines) renders a plumber character on the
landing page: **inline SVG, not GIF**, with an intro-to-corner animation and a Web Speech greeting.
It is original artwork. FixBuddy is better understood as an evolution of this component than as a
new one.

### 1.4 The blocking gap: repair state never reaches the client

This is the finding that matters most, and it blocks the central requirement of the FixBuddy brief
("the FixBuddy state must come from the existing FixBridge AI workflow; do not hardcode the AI
state in the UI").

The 20-state machine genuinely exists in `app/services/state_machine.py`, and
`RepairManagerAgent` genuinely computes the current state per turn into `RepairContext.state`.

**But that state is never serialized.** `ConversationResponse` in `app/schemas/repair.py` carries
only a four-value `ConversationStatus`:

```
Python RepairState (internal, 20):
  NEW · COLLECTING_INFORMATION · WAITING_FOR_IMAGE · IMAGE_ANALYSIS · SAFETY_CHECK
  INSUFFICIENT_INFORMATION · SAFE_DIY · PROFESSIONAL_REQUIRED · EMERGENCY
  REPAIR_PLAN_CREATED · STEP_IN_PROGRESS · WAITING_FOR_VERIFICATION · STEP_VERIFICATION
  STEP_FAILED · REPAIR_COMPLETED · CONTRACTOR_SEARCH · CONTRACTOR_REQUESTED
  CONTRACTOR_ACCEPTED · ESCALATED · CLOSED

ConversationResponse.status (on the wire, 4):
  NEED_MORE_INFORMATION · NEED_IMAGE · REPAIR_PLAN_READY · PROFESSIONAL_REQUIRED
```

Consequences, both briefs:

- A mascot driven by real state can distinguish **four** situations, not eighteen.
  `ANALYZING_IMAGE`, `CHECKING_SAFETY`, `VERIFYING_REPAIR` and `CONTRACTOR_SEARCH` are not
  observable by any client.
- The requested AI status sequence — "Understanding your problem… Checking your photo… Checking
  repair safety…" — cannot be driven honestly today. Rendering it anyway would be exactly the
  faked progress both briefs prohibit.

Note the asymmetry: `EMERGENCY` is a distinct internal state but collapses to
`PROFESSIONAL_REQUIRED` on the wire. The UI cannot currently tell "call a professional" apart from
"stop, this is dangerous". That is worth fixing on its own merits, independent of any mascot.

---

## 2. Recommended sequence

### Phase 0 — Widen the state contract (backend, prerequisite)

Everything else depends on this, and it is valuable even if FixBuddy is never built.

1. Add `state: RepairState` to `ConversationResponse`; populate from `RepairContext.state`, which
   the Repair Manager already computes and currently throws away.
2. Add `category` to the mascot's needs — already present on the response.
3. Widen Java `ConversationStatus` (or add a parallel `repairState` field) so `ConversationView`
   carries it through to the client. Keep the existing four-value field so nothing breaks.
4. Surface `EMERGENCY` distinctly from `PROFESSIONAL_REQUIRED`.

Additive only: existing clients keep working. This is the one piece I would do first regardless of
the platform decision.

**Effort:** ~half a day including tests.

### Phase 1 — FixBuddy core (PWA)

One configurable component, driven by the widened state:

```tsx
<FixBuddy category="PLUMBER" state="ANALYZING_IMAGE" size="medium" />
```

- `FixBuddyState.ts` — the state union, mirroring the backend enum, not re-derived
- `FixBuddyCategory.ts` — the nine categories, mapped from backend `category`
- `FixBuddyAnimationMap.ts` — one central `state → animation` table
- `FixBuddy.tsx` — resolves category + state to an asset, with fallbacks
- `FixBuddyMessage.tsx` — mascot beside an AI message
- `FixBuddyStatus.tsx` — the live status line during processing

**Fallback rules, in order:** category-specific asset → general asset → static SVG → the existing
inline mascot. An unknown state resolves to `GENERAL` + `IDLE` rather than throwing.

**Provider:** not needed. React Query already holds `ConversationView`, which will carry the state
after Phase 0. A separate `FixBuddyProvider` would duplicate the repair state machine on the
client, which the brief explicitly prohibits. `useFixBuddy()` should be a thin selector over the
existing query, not a second source of truth.

### Phase 2 — Home and chat

- Home: FixBuddy hero + "What needs fixing?" with `SHOW THE PROBLEM` primary, `Ask FixBridge` and
  `Speak` secondary. Retire the current corner-mascot intro on this screen.
- Chat (`/customer/assistant`): FixBuddy beside assistant messages; `FixBuddyStatus` replaces the
  current "Thinking…" line with the real state text.

### Phase 3 — Safety, steps, verification

The safety gate, repair plan, step ticking and photo verification all already work. This phase is
presentation only: swap the current banner and step list for `SafetyCard`, `RepairStepCard` and
`VerificationCard`, and show one step at a time instead of the full list.

**Constraint to preserve:** the composer is hidden on `EMERGENCY`. No mascot work may reintroduce a
path to DIY instructions when the safety verdict forbids them.

### Phase 4 — Category variants

Nine categories. Ship `GENERAL` first and let every category fall back to it; add specific art only
where it earns its weight. Nine categories × eleven states = 99 assets if built exhaustively, which
is not worth it — roughly 20 assets covers the visible cases.

### Phase 5 — Contractor escalation

`contractor-matches.tsx` already renders name, rating, distance, availability and out-of-range.
Missing against the brief: **verification status** (compliance is a filter, never surfaced) and a
**request button** (no per-contractor request endpoint exists; escalation goes through
`/customer/report`). Both are small backend additions.

### Phase 6 — Performance and accessibility

- Lazy-load animation assets per category; never load all variants
- Pause when the tab is hidden
- Honour `prefers-reduced-motion` — static frame, no motion
- Text always present; the mascot is never the only channel
- Accessible labels describing the *state*, e.g. "FixBridge AI is analyzing the uploaded repair image"

---

## 3. Screens: what the brief lists vs what exists

Of the 15 screens requested, most exist as routes already:

| Brief screen | Status |
|---|---|
| Home, AI Repair Chat, Repair Step, Repair Progress | exist (`/`, `/customer/assistant`) |
| Camera, Image Preview, Verification Camera | exist as file-input upload, not a camera UI |
| AI Image Analysis, Safety Decision, Verification Result | exist as inline chat states |
| Contractor Search | exists (`contractor-matches.tsx`, added today) |
| Contractor Details, Contractor Request | **do not exist** |
| Repair History, Profile | exist (`/customer`, `/account`) |

So this is largely a redesign of working screens plus two genuinely new ones — not fifteen new
screens.

---

## 4. What I would not build, and why

- **A React Native app**, unless you decide the PWA is insufficient. It duplicates a working
  frontend and is weeks of work, not incremental integration.
- **A client-side `FixBuddyProvider` holding repair state.** The backend is authoritative; a second
  copy will drift.
- **Lottie, initially.** The existing mascot is inline SVG and animates via CSS. Adding a Lottie
  runtime is ~50KB plus per-animation JSON, for assets that do not exist yet. Inline SVG + CSS
  covers idle, thinking, analyzing and success at a fraction of the weight. Revisit if the art
  demands it.
- **Nine categories × eleven states of artwork** up front. Build `GENERAL`, fall back to it, and
  add variants where they are actually seen.
- **The `mascot` object in the proposed response shape.** `state` and `category` are already on the
  response; a nested duplicate invites the two disagreeing.

---

## 5. Open decisions

1. **Platform.** PWA (recommended) or a new React Native app.
2. **Artwork.** No Figma designs were supplied. FixBuddy art needs to come from somewhere — I can
   extend the existing inline-SVG plumber, but nine polished category variants is a design job.
3. **Contractor request flow.** No per-contractor request endpoint exists. Should "Request" dispatch
   to a specific contractor, or continue through the existing report flow?
4. **Voice output.** The landing mascot speaks. Should FixBuddy narrate messages in chat, or stay
   silent unless asked?

---

## 6. Prerequisites unrelated to this work

Still outstanding, and only the account owner can do them:

- Rotate the Neon database password (exposed in chat, still live)
- Change the admin password at `/account`
