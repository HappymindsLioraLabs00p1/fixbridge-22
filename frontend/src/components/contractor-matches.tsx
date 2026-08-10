"use client";

import { useEffect, useState } from "react";
import { useMatchContractors } from "@/lib/hooks";
import type { ContractorMatch } from "@/lib/types";

/**
 * The contractors available for an escalated problem.
 *
 * <p>Shown at the point of escalation rather than behind another click: someone who has just been
 * told to stop and call a professional should be able to see who that could be without navigating
 * away and describing the problem a second time.
 */
export function ContractorMatches({ trade }: { trade: string }) {
  const [coords, setCoords] = useState<{ lat: number; lng: number } | null>(null);
  const [locationState, setLocationState] = useState<"idle" | "asking" | "denied">("idle");
  const { data, isPending, isError } = useMatchContractors(trade, coords);

  // Location is requested, never required. Ranking works without it — distance simply drops out of
  // the score — so a refusal degrades the list rather than blocking it.
  useEffect(() => {
    if (!("geolocation" in navigator)) return;
    setLocationState("asking");
    navigator.geolocation.getCurrentPosition(
      (p) => {
        setCoords({ lat: p.coords.latitude, lng: p.coords.longitude });
        setLocationState("idle");
      },
      () => setLocationState("denied"),
      { timeout: 8000, maximumAge: 300_000 },
    );
  }, []);

  if (isPending) {
    return <p className="mt-3 text-sm text-muted-foreground">Finding {trade}s near you…</p>;
  }
  if (isError) {
    return (
      <p className="mt-3 text-sm text-muted-foreground">
        I couldn&apos;t load contractors just now — you can still request one below.
      </p>
    );
  }

  const matches = data?.matches ?? [];
  if (matches.length === 0) {
    return (
      <p className="mt-3 text-sm text-muted-foreground">
        {data?.reason ?? `No ${trade} is available right now.`} Submitting a request will put you in
        the queue.
      </p>
    );
  }

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold">
          {matches.length} {trade}
          {matches.length > 1 ? "s" : ""} available
        </h3>
        {locationState === "denied" && (
          <span className="text-xs text-muted-foreground">
            Location off — not sorted by distance
          </span>
        )}
      </div>

      {/* When nobody has declared the trade the list is broader than requested, and saying so is
          better than implying a precise match. */}
      {data && !data.tradeFilterApplied && (
        <p className="mt-1 text-xs text-muted-foreground">
          Showing all available contractors — none have listed {trade} specifically.
        </p>
      )}

      <ul className="mt-2 space-y-2">
        {matches.map((m) => (
          <MatchRow key={m.contractorId} match={m} />
        ))}
      </ul>
    </div>
  );
}

function MatchRow({ match: m }: { match: ContractorMatch }) {
  const outOfRange = m.availability === "OUT_OF_RANGE";
  return (
    <li className="rounded-md border bg-card p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{m.businessName}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {/* No reviews is stated as such. A new contractor showing "0★" would read as bad
                rather than new. */}
            {m.rating !== null
              ? `★ ${m.rating.toFixed(1)} (${m.reviewCount})`
              : "No reviews yet"}
            {m.completedJobs > 0 && ` · ${m.completedJobs} job${m.completedJobs > 1 ? "s" : ""}`}
            {m.distanceMiles !== null && ` · ${m.distanceMiles} mi`}
          </p>
          {m.minTripChargeCents ? (
            <p className="mt-0.5 text-xs text-muted-foreground">
              Call-out from ${(m.minTripChargeCents / 100).toFixed(0)}
            </p>
          ) : null}
        </div>
        {outOfRange && (
          <span className="shrink-0 rounded-full px-2 py-0.5 text-xs"
                style={{ background: "var(--muted)", color: "var(--muted-foreground)" }}>
            Outside their area
          </span>
        )}
      </div>
    </li>
  );
}
