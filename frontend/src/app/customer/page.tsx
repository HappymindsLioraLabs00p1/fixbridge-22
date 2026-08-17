"use client";

import Link from "next/link";
import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import { useCreateProperty, useMyJobs, useProperties } from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { JobStatusBadge } from "@/components/status-badge";
import { HeroSearch } from "@/components/hero-search";
import { ServiceTiles } from "@/components/service-tiles";
import { TrustStrip } from "@/components/trust-strip";
import { JobProgress, progressCaption } from "@/components/job-progress";
import type { JobSummary } from "@/lib/types";

/**
 * The customer's home.
 *
 * <p>Ordered by what someone opening the app is most likely to want: report something new, check
 * the job already running, then everything else. Properties used to sit at the top because the
 * system needs one before a job can exist — but that is the platform's constraint, not the
 * homeowner's priority, so it now sits below the work.
 */
export default function CustomerDashboard() {
  return (
    <RequireRole role="customer">
      <div className="mx-auto max-w-6xl space-y-8 px-4 py-6 sm:py-8">
        <HeroSearch />

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">Popular services</h2>
            <Link href="/services" className="text-sm font-medium text-primary hover:underline">
              View all
            </Link>
          </div>
          <ServiceTiles />
        </section>

        <ActiveJobs />

        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Why {`FixBridge`}</h2>
          <TrustStrip />
        </section>

        <div className="grid gap-6 lg:grid-cols-2">
          <PropertiesCard />
          <AllRequestsCard />
        </div>
      </div>
    </RequireRole>
  );
}

/** Jobs currently in flight, shown the way the customer thinks about them: what's happening now. */
function ActiveJobs() {
  const { data: jobs, isLoading } = useMyJobs();

  const live = (jobs ?? []).filter(
    (j) => !["draft", "closed", "canceled", "refunded", "paid_out"].includes(j.status),
  );

  if (isLoading) {
    return <div className="h-40 animate-pulse rounded-xl border bg-muted/40" />;
  }
  if (live.length === 0) return null;

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Your bookings</h2>
        <span className="text-sm text-muted-foreground">{live.length} active</span>
      </div>
      <div className="grid gap-3 lg:grid-cols-2">
        {live.map((job) => (
          <BookingCard key={job.id} job={job} />
        ))}
      </div>
    </section>
  );
}

function BookingCard({ job }: { job: JobSummary }) {
  // Short, stable, and the same reference the contractor sees on their own card.
  const reference = job.id.slice(0, 8).toUpperCase();

  return (
    <Link
      href={`/customer/jobs/${job.id}`}
      className="block rounded-xl border bg-card p-4 transition-colors hover:border-primary/40"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs text-muted-foreground tabular">#{reference}</p>
          <p className="truncate font-semibold">{job.title ?? "Reported issue"}</p>
        </div>
        <JobStatusBadge status={job.status} />
      </div>

      <p className="mt-3 text-sm text-muted-foreground">{progressCaption(job.status)}</p>
      <div className="mt-3">
        <JobProgress status={job.status} />
      </div>
    </Link>
  );
}

function PropertiesCard() {
  const { data: properties, isLoading } = useProperties();
  const create = useCreateProperty();
  const [line1, setLine1] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");
  const [postalCode, setPostalCode] = useState("");

  function add(e: React.FormEvent) {
    e.preventDefault();
    create.mutate(
      { line1, city, state, postalCode },
      { onSuccess: () => { setLine1(""); setCity(""); setState(""); setPostalCode(""); } },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Your properties</CardTitle>
        <CardDescription>Add a property before reporting an issue.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : properties && properties.length > 0 ? (
          <ul className="space-y-2">
            {properties.map((p) => (
              <li key={p.id} className="rounded-lg border p-3 text-sm">
                <div className="font-medium">{p.line1}</div>
                <div className="text-muted-foreground">
                  {[p.city, p.state, p.postalCode].filter(Boolean).join(", ") || "Address incomplete"}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No properties yet.</p>
        )}

        <form onSubmit={add} className="space-y-3 border-t pt-4">
          <div className="space-y-1.5">
            <Label htmlFor="line1">Street address</Label>
            <Input id="line1" required value={line1} onChange={(e) => setLine1(e.target.value)} />
          </div>
          <div className="grid grid-cols-3 gap-2">
            <Input placeholder="City" value={city} onChange={(e) => setCity(e.target.value)} />
            <Input placeholder="State" value={state} onChange={(e) => setState(e.target.value)} />
            <Input placeholder="ZIP" value={postalCode} onChange={(e) => setPostalCode(e.target.value)} />
          </div>
          <Button type="submit" variant="outline" size="sm" disabled={create.isPending}>
            {create.isPending ? "Adding…" : "Add property"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

/** The full history, including the finished ones the bookings section deliberately leaves out. */
function AllRequestsCard() {
  const { data: jobs, isLoading } = useMyJobs();
  return (
    <Card>
      <CardHeader>
        <CardTitle>All requests</CardTitle>
        <CardDescription>Everything you&apos;ve reported, newest first.</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : jobs && jobs.length > 0 ? (
          <ul className="space-y-2">
            {jobs.map((j) => (
              <li key={j.id}>
                <Link
                  href={`/customer/jobs/${j.id}`}
                  className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm transition-colors hover:bg-muted"
                >
                  <span className="min-w-0 truncate font-medium">{j.title ?? "Issue"}</span>
                  <JobStatusBadge status={j.status} />
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <div className="rounded-lg border border-dashed p-6 text-center">
            <p className="text-sm font-medium">Nothing reported yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Describe a problem above and we&apos;ll take it from there.
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
