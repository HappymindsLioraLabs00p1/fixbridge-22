"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ServiceCard } from "@/lib/types";

/**
 * The trades, as a row of tiles.
 *
 * <p>A shortcut for people who already know what they need — the assistant above is for everyone
 * else. Six fit before "More" on a phone without the row becoming a scroll-hunt.
 *
 * <p>Order comes from the catalogue rather than being hard-coded, so a trade added on the server
 * appears here without a frontend release. Availability is shown honestly: a trade with no
 * dispatchable contractor is dimmed rather than hidden, because a customer who needs a roofer
 * should learn we cannot send one, not silently fail to find the option.
 */
const ICONS: Record<string, string> = {
  plumbing: "M7 3v6a5 5 0 0 0 10 0V3M12 14v7",
  electrical: "M13 2 4.5 13H11l-1 9 8.5-11H12l1-9Z",
  hvac: "M12 3v18M3 12h18M6 6l12 12M18 6 6 18",
  carpentry: "M14 4 20 10 10 20 4 20 4 14 14 4Z",
  painting: "M5 3h14v6H5zM12 9v4M9 13h6v8H9z",
  appliance: "M5 3h14v18H5zM5 9h14M9 6h.01M9 14h6",
  roofing: "M3 12 12 4l9 8M6 12v8h12v-8",
  handyman: "M14 6l4 4-8 8-4-4 8-8ZM4 20l3-1",
};

function TradeIcon({ code }: { code: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"
         strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6" aria-hidden>
      <path d={ICONS[code] ?? ICONS.handyman} />
    </svg>
  );
}

export function ServiceTiles({ limit = 6 }: { limit?: number }) {
  const { data, isPending } = useQuery({
    queryKey: ["catalog"],
    queryFn: () => api.get<ServiceCard[]>("/api/catalog"),
  });

  if (isPending) {
    return (
      <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-7">
        {Array.from({ length: limit + 1 }).map((_, i) => (
          <div key={i} className="h-24 animate-pulse rounded-xl border bg-muted/50" />
        ))}
      </div>
    );
  }

  const trades = (data ?? []).slice(0, limit);

  return (
    <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-7">
      {trades.map((t) => (
        <Link
          key={t.code}
          href="/customer/report"
          className={`flex flex-col items-center gap-2 rounded-xl border bg-card p-3 text-center transition-colors hover:border-primary/40 hover:bg-primary-subtle ${
            t.bookable ? "" : "opacity-60"
          }`}
        >
          <span className="grid h-11 w-11 place-items-center rounded-full bg-primary-subtle text-navy">
            <TradeIcon code={t.code} />
          </span>
          <span className="text-xs font-medium leading-tight">{t.name}</span>
          {!t.bookable && <span className="text-[10px] text-muted-foreground">No pro yet</span>}
        </Link>
      ))}
      <Link
        href="/services"
        className="flex flex-col items-center justify-center gap-2 rounded-xl border bg-card p-3 text-center transition-colors hover:border-primary/40 hover:bg-primary-subtle"
      >
        <span className="grid h-11 w-11 place-items-center rounded-full bg-muted text-navy" aria-hidden>
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
            <circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" />
          </svg>
        </span>
        <span className="text-xs font-medium">More</span>
      </Link>
    </div>
  );
}
