"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { RequireRole } from "@/components/require-auth";
import { uploadFile } from "@/lib/api";
import {
  useSendChatMessage,
  useStartConversation,
  useVerifyRepairStep,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { ConversationView, RepairStepView, VerificationView } from "@/lib/types";

type Bubble = { role: "customer" | "assistant"; text: string; images?: number };

export default function AssistantPage() {
  return (
    <RequireRole role="customer">
      <Assistant />
    </RequireRole>
  );
}

function Assistant() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [bubbles, setBubbles] = useState<Bubble[]>([]);
  const [view, setView] = useState<ConversationView | null>(null);
  const [draft, setDraft] = useState("");
  const [uploading, setUploading] = useState(false);

  const start = useStartConversation();
  const send = useSendChatMessage(conversationId);
  const bottom = useRef<HTMLDivElement>(null);
  // React runs effects twice in development. Without this guard that opens two conversations and
  // the second silently replaces the first, so the greeting and the transcript disagree.
  const opened = useRef(false);

  // Open a conversation as soon as the screen loads — asking someone to press "start" before they
  // can describe a leak is a pointless extra step.
  useEffect(() => {
    if (opened.current) return;
    opened.current = true;
    start.mutate(undefined, {
      onSuccess: (c) => {
        setConversationId(c.id);
        setView(c);
        setBubbles([{ role: "assistant", text: c.message }]);
      },
      onError: () =>
        setBubbles([
          {
            role: "assistant",
            text: "I couldn't start a conversation just now. Please refresh and try again.",
          },
        ]),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: "smooth" });
  }, [bubbles, view?.plan]);

  function submit(text: string, imageKeys: string[] = []) {
    if (!conversationId || (!text.trim() && imageKeys.length === 0)) return;
    setBubbles((b) => [...b, { role: "customer", text: text || "Photo sent", images: imageKeys.length }]);
    setDraft("");
    send.mutate(
      { text, imageKeys },
      {
        onSuccess: (c) => {
          setView(c);
          setBubbles((b) => [...b, { role: "assistant", text: c.message }]);
        },
        onError: () =>
          setBubbles((b) => [
            ...b,
            { role: "assistant", text: "Something went wrong there. Please try again." },
          ]),
      },
    );
  }

  /**
   * Some quick replies are actions rather than answers. "Yes, guide me" sent as a message made the
   * assistant re-read the transcript and produce the plan a second time; "Contact a professional"
   * belongs on the reporting flow, not in the chat.
   */
  function quickReply(reply: string) {
    const lower = reply.toLowerCase();
    if (lower.startsWith("yes, guide")) {
      setBubbles((b) => [
        ...b,
        { role: "customer", text: reply },
        { role: "assistant", text: "Great — work through the steps below and take your time." },
      ]);
      return;
    }
    if (lower.includes("professional")) {
      window.location.href = "/customer/report";
      return;
    }
    if (lower.startsWith("skip")) {
      submit("I'd rather not send a photo");
      return;
    }
    submit(reply);
  }

  async function sendPhotos(files: FileList | null) {
    if (!files?.length) return;
    setUploading(true);
    try {
      const keys: string[] = [];
      for (const f of Array.from(files).slice(0, 4)) keys.push(await uploadFile(f));
      submit("", keys);
    } finally {
      setUploading(false);
    }
  }

  const emergency = view?.status === "EMERGENCY";
  const professional = view?.status === "PROFESSIONAL_REQUIRED";
  const busy = send.isPending || uploading;

  return (
    <div className="mx-auto flex min-h-[calc(100vh-4rem)] max-w-2xl flex-col px-4 py-6">
      <header className="mb-4">
        <h1 className="text-2xl font-bold">FixBridge Assistant</h1>
        <p className="text-sm text-muted-foreground">
          Describe what&apos;s wrong, or send a photo. I&apos;ll help where it&apos;s safe to.
        </p>
      </header>

      {/* Escalation is a banner, not a chat bubble — it must not scroll away. */}
      {(emergency || professional) && (
        <div
          className="mb-4 rounded-lg border-2 p-4"
          style={{
            borderColor: emergency ? "var(--destructive)" : "var(--warning)",
            background: "var(--card)",
          }}
        >
          <p className="font-semibold" style={{ color: emergency ? "var(--destructive)" : "var(--warning)" }}>
            {emergency ? "Stop — this needs immediate attention" : "This needs a professional"}
          </p>
          <p className="mt-1 text-sm">{view?.message}</p>
          <Link href="/customer/report" className="mt-3 inline-block">
            <Button>Request a professional</Button>
          </Link>
        </div>
      )}

      {/* Transcript */}
      <div className="flex-1 space-y-3">
        {bubbles.map((b, i) => (
          <div key={i} className={b.role === "customer" ? "flex justify-end" : "flex justify-start"}>
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm ${
                b.role === "customer"
                  ? "bg-primary text-primary-foreground"
                  : "border bg-card"
              }`}
            >
              {b.text}
              {b.images ? (
                <span className="mt-1 block text-xs opacity-80">
                  📷 {b.images} photo{b.images > 1 ? "s" : ""}
                </span>
              ) : null}
            </div>
          </div>
        ))}
        {busy && (
          <div className="flex justify-start">
            <div className="rounded-2xl border bg-card px-4 py-2.5 text-sm text-muted-foreground">
              {uploading ? "Sending your photo…" : "Thinking…"}
            </div>
          </div>
        )}

        {view?.plan && <RepairPlan plan={view.plan} />}
        <div ref={bottom} />
      </div>

      {/* Composer */}
      {!emergency && (
        <div className="sticky bottom-0 mt-4 space-y-2 bg-[var(--background)] pt-3">
          {view?.quickReplies?.length ? (
            <div className="flex flex-wrap gap-2">
              {view.quickReplies.map((q) => (
                <Button key={q} size="sm" variant="outline" disabled={busy} onClick={() => quickReply(q)}>
                  {q}
                </Button>
              ))}
            </div>
          ) : null}

          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              submit(draft);
            }}
          >
            <label className="flex shrink-0 cursor-pointer items-center rounded-md border px-3 text-lg"
                   title="Send a photo">
              📷
              <input type="file" accept="image/*" multiple className="hidden"
                     onChange={(e) => sendPhotos(e.target.files)} />
            </label>
            <Input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder={view?.requiresImage ? "Send a photo, or describe it…" : "Describe the problem…"}
              disabled={busy}
            />
            <Button type="submit" disabled={busy || !draft.trim()}>
              Send
            </Button>
          </form>
        </div>
      )}
    </div>
  );
}

function RepairPlan({ plan }: { plan: NonNullable<ConversationView["plan"]> }) {
  return (
    <div className="rounded-lg border bg-card p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="font-semibold">Your repair plan</h2>
        <span className="font-mono text-xs text-muted-foreground">
          {plan.steps.length} steps{plan.estimatedMinutes ? ` · about ${plan.estimatedMinutes} min` : ""}
        </span>
      </div>

      <ol className="mt-3 space-y-3">
        {(plan.steps ?? []).map((s) => (
          <Step key={s.id} step={s} />
        ))}
      </ol>

      {(plan.stopConditions ?? []).length > 0 && (
        <div className="mt-4 rounded-md border-l-2 p-3 text-sm" style={{ borderColor: "var(--warning)" }}>
          <p className="font-medium">Stop straight away if:</p>
          <ul className="mt-1 list-disc pl-5 text-muted-foreground">
            {plan.stopConditions.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        </div>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        AI assistance only. Final assessment must be performed by a qualified professional.
      </p>
    </div>
  );
}

function Step({ step }: { step: RepairStepView }) {
  const verify = useVerifyRepairStep();
  const [result, setResult] = useState<VerificationView | null>(null);
  const [busy, setBusy] = useState(false);
  // Steps that need no photo still need a way to be marked off, otherwise the plan is a wall of
  // text with no sense of progress.
  const [ticked, setTicked] = useState(false);

  async function checkWithPhoto(files: FileList | null) {
    if (!files?.length) return;
    setBusy(true);
    try {
      const keys = [await uploadFile(files[0])];
      verify.mutate({ stepId: step.id, imageKeys: keys }, { onSuccess: setResult });
    } finally {
      setBusy(false);
    }
  }

  const done = ticked || result?.result === "STEP_COMPLETED" || step.state === "verified";

  return (
    <li className="rounded-md border p-3">
      <div className="flex items-start gap-3">
        <span
          className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold"
          style={{
            background: done ? "var(--success)" : "var(--muted)",
            color: done ? "#fff" : "var(--muted-foreground)",
          }}
        >
          {done ? "✓" : step.number}
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium">{step.instruction}</p>
          {step.why && <p className="mt-1 text-xs text-muted-foreground">Why: {step.why}</p>}
          {(step.tools ?? []).length > 0 && (
            <p className="mt-1 text-xs text-muted-foreground">You&apos;ll need: {(step.tools ?? []).join(", ")}</p>
          )}
          {(step.warnings ?? []).map((w) => (
            <p key={w} className="mt-1 text-xs font-medium" style={{ color: "var(--warning)" }}>
              ⚠ {w}
            </p>
          ))}

          {!done && (
            <div className="mt-2 flex flex-wrap gap-2">
              <Button size="sm" variant="outline" onClick={() => setTicked(true)}>
                I&apos;ve done this
              </Button>
              {step.requiresImageVerification && (
                <label className="inline-flex cursor-pointer items-center gap-2 rounded-md border px-3 py-1.5 text-xs">
                  {busy || verify.isPending ? "Checking…" : "📷 Check with a photo"}
                  <input type="file" accept="image/*" className="hidden" disabled={busy}
                         onChange={(e) => checkWithPhoto(e.target.files)} />
                </label>
              )}
            </div>
          )}

          {result && (
            <p
              className="mt-2 text-xs"
              style={{
                color:
                  result.result === "STEP_COMPLETED"
                    ? "var(--success)"
                    : result.result === "ESCALATE"
                      ? "var(--destructive)"
                      : "var(--warning)",
              }}
            >
              {result.reason} — {result.nextAction}
            </p>
          )}
        </div>
      </div>
    </li>
  );
}
