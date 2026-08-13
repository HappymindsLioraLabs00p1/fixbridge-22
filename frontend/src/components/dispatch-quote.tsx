"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import type { DispatchQuote } from "@/lib/types";

/**
 * What a homeowner will pay, shown before anyone is dispatched.
 *
 * <p>The design problem here is one specific misreading: FixBridge is free during beta, and a
 * customer who sees "$0" at the top assumes the visit is free too. They find out otherwise when a
 * contractor bills them, having already driven to the property — which is a dispute, a refund and
 * a lost customer.
 *
 * <p>So the visit fee is not a line item under a total. It is the largest number on the card, the
 * $0 is explicitly labelled as *not* covering it, and the button says what is being agreed to
 * rather than "Continue".
 */
export function DispatchQuoteCard({
  jobId,
  trade,
  emergency = false,
  onAccept,
}: {
  jobId: string;
  trade?: string | null;
  emergency?: boolean;
  onAccept: () => void;
}) {
  const [accepted, setAccepted] = useState(false);

  const { data, isPending, isError } = useQuery({
    queryKey: ["dispatch-quote", jobId, trade, emergency],
    queryFn: () => {
      const q = new URLSearchParams();
      if (trade) q.set("trade", trade);
      if (emergency) q.set("emergency", "true");
      return api.get<DispatchQuote>(`/api/jobs/${jobId}/dispatch-quote?${q}`);
    },
  });

  const money = (cents: number) => `$${(cents / 100).toFixed(0)}`;

  if (isPending) return <p className="text-sm text-muted-foreground">Working out the cost…</p>;
  if (isError || !data) {
    return (
      <p className="text-sm text-muted-foreground">
        We couldn&apos;t work out the visit fee just now. We&apos;ll confirm it with you before
        anyone is dispatched.
      </p>
    );
  }

  const range =
    data.visitFeeLowCents === null
      ? null
      : data.visitFeeLowCents === data.visitFeeHighCents
        ? money(data.visitFeeLowCents)
        : `${money(data.visitFeeLowCents)}–${money(data.visitFeeHighCents!)}`;

  return (
    <div className="rounded-xl border bg-card p-5">
      <h3 className="font-semibold">Before we send someone</h3>

      {/* FixBridge fee. Stated first because it is the good news, and immediately qualified so it
          cannot be read as covering the visit. */}
      <div className="mt-4 flex items-baseline justify-between gap-4 border-b pb-3">
        <div>
          <p className="text-sm font-medium">FixBridge fee</p>
          <p className="text-xs text-muted-foreground">Our coordination fee — waived during beta</p>
        </div>
        <p className="text-lg font-semibold" style={{ color: "var(--success)" }}>$0</p>
      </div>

      {/* The visit fee. Deliberately the largest thing on the card. */}
      <div className="mt-3 flex items-baseline justify-between gap-4 border-b pb-3">
        <div>
          <p className="text-sm font-medium">Contractor visit fee</p>
          <p className="text-xs text-muted-foreground">
            {data.visitFeeBasis === "EMERGENCY"
              ? "Emergency call-out"
              : data.visitFeeBasis === "WEEKEND"
                ? "Weekend rate"
                : data.visitFeeBasis === "AFTER_HOURS"
                  ? "Outside working hours"
                  : "Paid to the contractor for coming out and diagnosing"}
          </p>
        </div>
        <p className="text-2xl font-bold tracking-tight">{range ?? "To be confirmed"}</p>
      </div>

      {/* The repair itself. Named so its absence is understood, not discovered later. */}
      <div className="mt-3 flex items-baseline justify-between gap-4">
        <div>
          <p className="text-sm font-medium">Repair cost</p>
          <p className="text-xs text-muted-foreground">
            Quoted after diagnosis — you approve it before any work starts
          </p>
        </div>
        <p className="text-sm text-muted-foreground">Not yet known</p>
      </div>

      {/* The sentence that prevents the misunderstanding. Worth the space. */}
      <p className="mt-4 rounded-md border-l-2 p-3 text-sm"
         style={{ borderColor: "var(--warning)", background: "var(--background)" }}>
        <strong>The $0 is our fee, not the visit.</strong>{" "}
        {range
          ? `The contractor charges ${range} to come out and diagnose the problem, and that is payable even if you decide not to go ahead with the repair.`
          : "No contractor has published a visit fee for this trade yet, so we'll confirm the amount with you before anyone is dispatched."}
      </p>

      <label className="mt-4 flex items-start gap-3 text-sm">
        <input
          type="checkbox"
          checked={accepted}
          onChange={(e) => setAccepted(e.target.checked)}
          className="mt-0.5 h-4 w-4 shrink-0"
        />
        <span>
          I understand the contractor&apos;s visit fee{range ? ` of ${range}` : ""} is separate from
          FixBridge&apos;s $0 fee, and is payable for the visit itself.
        </span>
      </label>

      <Button className="mt-4" disabled={!accepted} onClick={onAccept}>
        Accept visit fee &amp; request a contractor
      </Button>

      <p className="mt-2 text-xs text-muted-foreground">
        {data.availableContractors > 0
          ? `${data.availableContractors} verified ${data.availableContractors === 1 ? "contractor" : "contractors"} available for this trade.`
          : "No verified contractor is available for this trade yet — we'll be in touch."}
      </p>
    </div>
  );
}
