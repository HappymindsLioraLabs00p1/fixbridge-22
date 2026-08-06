"use client";

import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import {
  useInvitations,
  useOnboardContractor,
  useSubmitBid,
  useSubmitCompletion,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { UrgencyBadge } from "@/components/status-badge";
import { formatCents } from "@/lib/utils";
import type { ContractorInvitation } from "@/lib/types";

export default function ContractorDashboard() {
  return (
    <RequireRole role="contractor">
      <div className="mx-auto max-w-5xl space-y-8 px-4 py-8">
        <div>
          <h1 className="text-2xl font-bold">Contractor workspace</h1>
          <p className="text-sm text-muted-foreground">
            Complete onboarding, review invitations, and submit confidential net bids.
          </p>
        </div>
        <OnboardCard />
        <InvitationsList />
      </div>
    </RequireRole>
  );
}

function OnboardCard() {
  const onboard = useOnboardContractor();
  const [businessName, setBusinessName] = useState("");
  const [contactPhone, setContactPhone] = useState("");
  return (
    <Card>
      <CardHeader>
        <CardTitle>Onboarding &amp; payouts</CardTitle>
        <CardDescription>
          Set up your business and Stripe Connect payouts. You can&apos;t receive jobs or payouts until
          onboarding is complete.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            onboard.mutate(
              { businessName, contactPhone },
              {
                onSuccess: (data) => {
                  // Live mode returns a Stripe-hosted onboarding link; stub mode returns none.
                  const url = (data as { onboardingUrl?: string })?.onboardingUrl;
                  if (url) window.location.href = url;
                },
              },
            );
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="biz">Business name</Label>
            <Input id="biz" required value={businessName} onChange={(e) => setBusinessName(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="phone">Contact phone</Label>
            <Input id="phone" value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} />
          </div>
          <Button type="submit" disabled={onboard.isPending}>
            {onboard.isPending ? "Saving…" : "Complete onboarding"}
          </Button>
          {onboard.isSuccess && <span className="text-sm text-[var(--success)]">Onboarding complete ✓</span>}
        </form>
      </CardContent>
    </Card>
  );
}

function InvitationsList() {
  const { data: invitations, isLoading } = useInvitations();
  return (
    <Card>
      <CardHeader>
        <CardTitle>Job invitations</CardTitle>
        <CardDescription>
          You see the general area, trade, urgency and expected net — never the full address or the
          customer&apos;s retail price.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : invitations && invitations.length > 0 ? (
          invitations.map((inv) => <InvitationRow key={inv.jobId} inv={inv} />)
        ) : (
          <p className="text-sm text-muted-foreground">No invitations yet.</p>
        )}
      </CardContent>
    </Card>
  );
}

function InvitationRow({ inv }: { inv: ContractorInvitation }) {
  const bid = useSubmitBid(inv.jobId);
  const completion = useSubmitCompletion(inv.jobId);
  const [open, setOpen] = useState(false);
  const [labor, setLabor] = useState(0);
  const [materials, setMaterials] = useState(0);
  const [travel, setTravel] = useState(0);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    bid.mutate({
      laborCents: labor * 100,
      materialsCents: materials * 100,
      equipmentCents: 0,
      travelCents: travel * 100,
      permitCents: 0,
      disposalCents: 0,
    });
  }

  return (
    <div className="rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <span className="font-medium capitalize">{inv.recommendedTrade ?? "Service"}</span>
            <UrgencyBadge urgency={inv.urgency} />
          </div>
          <p className="text-sm text-muted-foreground">{inv.generalArea}</p>
        </div>
        <div className="text-right text-sm">
          <p className="text-muted-foreground">Expected net</p>
          <p className="font-semibold">{formatCents(inv.expectedNetCents)}</p>
        </div>
      </div>

      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" onClick={() => setOpen((o) => !o)}>
          {open ? "Cancel" : "Submit net bid"}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => completion.mutate({ summary: "Work completed as scoped." })}
          disabled={completion.isPending}
        >
          Mark work complete
        </Button>
        {bid.isSuccess && <span className="self-center text-sm text-[var(--success)]">Bid submitted ✓</span>}
        {completion.isSuccess && (
          <span className="self-center text-sm text-[var(--success)]">Completion submitted ✓</span>
        )}
      </div>

      {open && (
        <form onSubmit={submit} className="mt-3 grid grid-cols-3 gap-2 border-t pt-3">
          <div className="space-y-1">
            <Label className="text-xs">Labor ($)</Label>
            <Input type="number" min={0} value={labor} onChange={(e) => setLabor(+e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label className="text-xs">Materials ($)</Label>
            <Input type="number" min={0} value={materials} onChange={(e) => setMaterials(+e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label className="text-xs">Travel ($)</Label>
            <Input type="number" min={0} value={travel} onChange={(e) => setTravel(+e.target.value)} />
          </div>
          <div className="col-span-3">
            <Button type="submit" size="sm" disabled={bid.isPending}>
              {bid.isPending ? "Submitting…" : `Submit confidential bid (${formatCents((labor + materials + travel) * 100)})`}
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
