"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import type { ServiceCard } from "@/lib/types";

/**
 * The shop window: what FixBridge does, roughly what it costs, and who can do it.
 *
 * <p>Readable without an account on purpose. Someone still deciding whether to use FixBridge
 * shouldn't have to register to see a price list.
 *
 * <p>Prices come from jobs actually priced through the platform. A trade nobody has booked yet
 * shows "Get a quote" rather than a plausible-looking number — the first real bill that contradicts
 * an invented range costs more trust than the missing number ever would.
 */
export default function ServicesPage() {
  const { data, isPending, isError } = useQuery({
    queryKey: ["catalog"],
    queryFn: () => api.get<ServiceCard[]>("/api/catalog"),
  });

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <header className="mb-6">
        <p className="font-mono text-[11px] uppercase tracking-[0.18em] text-primary">
          NYC &amp; Long Island
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">What needs fixing?</h1>
        <p className="mt-2 max-w-prose text-muted-foreground">
          Browse a trade for typical pricing, or describe the problem and let the assistant work out
          what you need.
        </p>
        <div className="mt-4 flex flex-wrap gap-2">
          <Link href="/customer/assistant">
            <Button>Describe the problem →</Button>
          </Link>
          <Link href="/customer/report">
            <Button variant="outline">Request a professional</Button>
          </Link>
        </div>
      </header>

      {isPending && <p className="text-sm text-muted-foreground">Loading services…</p>}
      {isError && (
        <p className="text-sm text-muted-foreground">
          Couldn&apos;t load the service list just now. You can still describe a problem above.
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        {(data ?? []).map((s) => (
          <ServiceTile key={s.code} service={s} />
        ))}
      </div>

      <p className="mt-6 text-xs text-muted-foreground">
        Ranges are averages of work already completed through FixBridge, shown with the number of
        jobs behind them. Your quote depends on what the job turns out to need.
      </p>
    </div>
  );
}

function ServiceTile({ service: s }: { service: ServiceCard }) {
  const money = (cents: number) => `$${Math.round(cents / 100)}`;
  return (
    <div className="flex flex-col rounded-xl border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <h2 className="font-semibold">{s.name}</h2>
        {/* A trade with no reviews reads as new, never as zero stars. */}
        {s.averageRating !== null ? (
          <span className="shrink-0 text-sm">
            ★ {s.averageRating.toFixed(1)}{" "}
            <span className="text-muted-foreground">({s.reviewCount})</span>
          </span>
        ) : (
          <span className="shrink-0 text-xs text-muted-foreground">New</span>
        )}
      </div>

      <div className="mt-2">
        {s.typicalLowCents !== null && s.typicalHighCents !== null ? (
          <>
            <p className="text-lg font-semibold tracking-tight">
              {money(s.typicalLowCents)}–{money(s.typicalHighCents)}
            </p>
            <p className="text-xs text-muted-foreground">
              typical, from {s.pricedJobs} completed job{s.pricedJobs === 1 ? "" : "s"}
            </p>
          </>
        ) : (
          <>
            <p className="text-lg font-semibold tracking-tight">Get a quote</p>
            <p className="text-xs text-muted-foreground">
              No completed jobs yet — priced after assessment
            </p>
          </>
        )}
      </div>

      <div className="mt-3 flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">
          {s.availableContractors > 0
            ? `${s.availableContractors} verified ${s.availableContractors === 1 ? "pro" : "pros"} available`
            : "No verified pro available yet"}
        </span>
        <Link href={`/customer/report?trade=${encodeURIComponent(s.code)}`}>
          <Button size="sm" variant={s.bookable ? "default" : "outline"}>
            {s.bookable ? "Book" : "Enquire"}
          </Button>
        </Link>
      </div>
    </div>
  );
}
