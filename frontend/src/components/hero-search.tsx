"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { brand } from "@/config/brand";
import { useHydrated } from "@/lib/use-hydrated";

/**
 * The front door: describe the problem in your own words.
 *
 * <p>Everything else on the dashboard is something you come back to. This is the one thing a
 * homeowner arrives to do, so it gets the navy ground, the largest type on the page, and the only
 * filled orange button above the fold.
 *
 * <p>The field takes plain language rather than a category, because someone with water coming
 * through a ceiling does not know whether that is plumbing or roofing — working that out is the
 * product's job, not theirs.
 */
export function HeroSearch() {
  const router = useRouter();
  const hydrated = useHydrated();
  const [problem, setProblem] = useState("");

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const text = problem.trim();
    // Carried into the assistant so the first thing they typed is not thrown away and retyped.
    router.push(text ? `/customer/assistant?q=${encodeURIComponent(text)}` : "/customer/assistant");
  }

  return (
    <section className="overflow-hidden rounded-2xl bg-navy text-navy-foreground">
      <div className="relative px-6 py-10 sm:px-10 sm:py-12">
        {/* A wash rather than a photograph: the skyline in the reference is decoration, and at
            small sizes it competes with the one control that matters. */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 opacity-70"
          style={{
            background:
              "radial-gradient(120% 100% at 85% 0%, rgba(255,107,0,0.22) 0%, rgba(7,26,61,0) 55%)",
          }}
        />
        <div className="relative max-w-2xl">
          <h1 className="text-3xl font-semibold leading-tight sm:text-4xl">
            What can we <span className="text-primary">fix</span> for you?
          </h1>
          <p className="mt-3 text-sm text-white/70 sm:text-base">
            Describe the issue or upload a photo, and the assistant works out what you need.
          </p>

          <form onSubmit={submit} className="mt-6 flex flex-col gap-2 sm:flex-row">
            <input
              value={problem}
              onChange={(e) => setProblem(e.target.value)}
              placeholder="Ex: kitchen sink leaking"
              aria-label="Describe the problem"
              className="h-12 flex-1 rounded-xl border border-white/15 bg-white/10 px-4 text-sm text-white placeholder:text-white/50 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/40"
            />
            <button
              type="submit"
              disabled={!hydrated}
              className="h-12 shrink-0 rounded-xl bg-primary px-6 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 focus:outline-none focus:ring-2 focus:ring-primary/50 disabled:opacity-60"
            >
              Find solution
            </button>
          </form>

          <p className="mt-4 text-xs text-white/60">
            Trusted across {brand.region} · licensed and insured professionals only
          </p>
        </div>
      </div>
    </section>
  );
}
