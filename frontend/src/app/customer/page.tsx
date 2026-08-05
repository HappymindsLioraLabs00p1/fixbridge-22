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

export default function CustomerDashboard() {
  return (
    <RequireRole role="customer">
      <div className="mx-auto max-w-5xl space-y-8 px-4 py-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">Your properties &amp; requests</h1>
            <p className="text-sm text-muted-foreground">Report an issue and track it to completion.</p>
          </div>
          <Link href="/customer/report">
            <Button>Report an issue</Button>
          </Link>
        </div>
        <div className="grid gap-8 lg:grid-cols-2">
          <PropertiesCard />
          <JobsCard />
        </div>
      </div>
    </RequireRole>
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
        <CardTitle>Properties</CardTitle>
        <CardDescription>Add a property before reporting an issue.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : properties && properties.length > 0 ? (
          <ul className="space-y-2">
            {properties.map((p) => (
              <li key={p.id} className="rounded-md border p-3 text-sm">
                <div className="font-medium">{p.line1}</div>
                <div className="text-muted-foreground">
                  {[p.city, p.state, p.postalCode].filter(Boolean).join(", ")}
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

function JobsCard() {
  const { data: jobs, isLoading } = useMyJobs();
  return (
    <Card>
      <CardHeader>
        <CardTitle>Requests</CardTitle>
        <CardDescription>Your reported issues and their status.</CardDescription>
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
                  className="flex items-center justify-between rounded-md border p-3 text-sm hover:bg-muted"
                >
                  <span className="font-medium">{j.title ?? "Issue"}</span>
                  <JobStatusBadge status={j.status} />
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No requests yet.</p>
        )}
      </CardContent>
    </Card>
  );
}
