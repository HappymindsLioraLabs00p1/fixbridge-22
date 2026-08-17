"use client";

import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import { ApiError, uploadFile } from "@/lib/api";
import {
  useInvitations,
  useMyCompliance,
  useOnboardContractor,
  useSubmitDocument,
  useSubmitBid,
  useSubmitChangeOrder,
  useSubmitCompletion,
  useStartWork,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/select";
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
        <ComplianceCard />
        <InvitationsList />
      </div>
    </RequireRole>
  );
}

const DOC_KINDS = [
  { value: "license", label: "Trade licence", required: true },
  { value: "insurance", label: "Liability insurance", required: true },
  { value: "workers_comp", label: "Workers' compensation", required: false },
  { value: "w9", label: "W-9", required: false },
];

function ComplianceCard() {
  const { data: compliance } = useMyCompliance();
  const submit = useSubmitDocument();
  const [kind, setKind] = useState("license");
  const [number, setNumber] = useState("");
  const [jurisdiction, setJurisdiction] = useState("");
  const [expiresOn, setExpiresOn] = useState("");

  return (
    <Card>
      <CardHeader>
        <CardTitle>Licensing &amp; insurance</CardTitle>
        <CardDescription>
          We can&apos;t send you to a customer&apos;s property without a current trade licence and
          liability insurance on file.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {compliance && (
          <div
            className="rounded-md border p-3 text-sm"
            style={{ borderColor: compliance.compliant ? "var(--success)" : "var(--warning)" }}
          >
            {compliance.compliant ? (
              <p className="font-medium text-[var(--success)]">
                You&apos;re cleared for dispatch ✓
              </p>
            ) : (
              <p className="font-medium text-[var(--warning)]">
                Not cleared for dispatch — {compliance.missingOrUnverified.map((k) => k.replace("_", " ")).join(" and ")}{" "}
                {compliance.expired.length > 0 ? `(expired: ${compliance.expired.join(", ")})` : "outstanding"}.
              </p>
            )}
          </div>
        )}

        {compliance && compliance.documents.length > 0 && (
          <div className="space-y-2">
            {compliance.documents.map((d) => (
              <div key={d.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
                <span className="capitalize">{d.kind.replace("_", " ")}</span>
                <span className="text-muted-foreground">
                  {d.number ? `#${d.number}` : "—"}
                  {d.expiresOn && <> · expires {d.expiresOn}</>}
                  {d.daysUntilExpiry != null && d.daysUntilExpiry <= 30 && d.daysUntilExpiry >= 0 && (
                    <span className="text-[var(--warning)]"> · {d.daysUntilExpiry} days left</span>
                  )}
                </span>
                <span
                  className="rounded-full px-2 py-0.5 text-xs capitalize"
                  style={{
                    background:
                      d.status === "valid" ? "var(--success)" : d.status === "pending" ? "var(--muted)" : "var(--destructive)",
                    color: d.status === "pending" ? "var(--muted-foreground)" : "#fff",
                  }}
                >
                  {d.status}
                </span>
              </div>
            ))}
          </div>
        )}

        <form
          className="space-y-2 border-t pt-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit.mutate(
              { kind, number, jurisdiction, expiresOn: expiresOn || undefined },
              { onSuccess: () => { setNumber(""); setJurisdiction(""); setExpiresOn(""); } },
            );
          }}
        >
          <Label className="text-xs">Add or renew a document</Label>
          <div className="grid gap-2 sm:grid-cols-4">
            <Select value={kind} onChange={(e) => setKind(e.target.value)}>
              {DOC_KINDS.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                  {k.required ? " (required)" : ""}
                </option>
              ))}
            </Select>
            <Input placeholder="number" value={number} onChange={(e) => setNumber(e.target.value)} />
            <Input placeholder="state / jurisdiction" value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} />
            <Input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} />
          </div>
          <Button type="submit" size="sm" variant="outline" disabled={submit.isPending}>
            {submit.isPending ? "Submitting…" : "Submit for review"}
          </Button>
          {submit.isSuccess && (
            <p className="text-xs text-muted-foreground">Submitted — an admin will verify it shortly.</p>
          )}
        </form>
      </CardContent>
    </Card>
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
  // "accepted" is set when the contractor's bid lands, so it survives a reload where the mutation's
  // own success state does not.
  const alreadyBid = inv.status === "accepted";
  // A job is startable once it is booked, and only then. Once started, the work actions apply.
  const canStart = inv.jobStatus === "scheduled";
  const inProgress = inv.jobStatus === "work_started" || inv.jobStatus === "change_order_pending";
  const start = useStartWork(inv.jobId);
  const bid = useSubmitBid(inv.jobId);
  const completion = useSubmitCompletion(inv.jobId);
  const changeOrder = useSubmitChangeOrder(inv.jobId);
  const [open, setOpen] = useState(false);
  const [labor, setLabor] = useState(0);
  const [materials, setMaterials] = useState(0);
  const [travel, setTravel] = useState(0);
  const [coOpen, setCoOpen] = useState(false);
  const [coDesc, setCoDesc] = useState("");
  const [coNet, setCoNet] = useState(0);
  const [coDays, setCoDays] = useState(0);
  // Completion proof
  const [doneOpen, setDoneOpen] = useState(false);
  const [summary, setSummary] = useState("");
  const [materialsUsed, setMaterialsUsed] = useState("");
  const [warranty, setWarranty] = useState("");
  const [beforeFiles, setBeforeFiles] = useState<File[]>([]);
  const [afterFiles, setAfterFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);

  async function submitCompletion(e: React.FormEvent) {
    e.preventDefault();
    setUploading(true);
    try {
      const beforeKeys: string[] = [];
      const afterKeys: string[] = [];
      for (const f of beforeFiles) beforeKeys.push(await uploadFile(f));
      for (const f of afterFiles) afterKeys.push(await uploadFile(f));
      completion.mutate(
        {
          summary,
          materialsUsed: materialsUsed || undefined,
          warrantyText: warranty || undefined,
          completedAt: new Date().toISOString(),
          beforeKeys,
          afterKeys,
        },
        { onSuccess: () => setDoneOpen(false) },
      );
    } finally {
      setUploading(false);
    }
  }

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

  // Every plumbing job looks alike on this card — same trade, same urgency, same expected net — so
  // without a reference a contractor cannot tell which invitation they are answering.
  const reference = inv.jobId.slice(0, 8).toUpperCase();

  return (
    <div className="rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <span className="font-medium capitalize">{inv.recommendedTrade ?? "Service"}</span>
            <UrgencyBadge urgency={inv.urgency} />
          </div>
          <p className="text-sm text-muted-foreground">
            {inv.generalArea} · Job {reference}
          </p>
        </div>
        <div className="text-right text-sm">
          <p className="text-muted-foreground">Expected net</p>
          <p className="font-semibold">{formatCents(inv.expectedNetCents)}</p>
        </div>
      </div>

      <div className="mt-3 flex gap-2">
        {/* An invitation already answered must not offer to answer it again: the server rejects a
            second bid, so the button could only ever produce an error. The success tick below is
            mutation state and disappears on reload — this reads from the invitation itself. */}
        {alreadyBid ? (
          <span className="self-center text-sm text-muted-foreground">Bid submitted ✓</span>
        ) : (
          <Button size="sm" variant="outline" onClick={() => setOpen((o) => !o)}>
            {open ? "Cancel" : "Submit net bid"}
          </Button>
        )}
        {/* Gated on where the JOB has got to, not the invitation. Every action used to be offered
            on every invitation, so a job nobody had started still showed "Mark work complete". The
            server enforces the same rules — this only stops offering what it would refuse. */}
        {canStart && (
          <Button size="sm" disabled={start.isPending} onClick={() => start.mutate()}>
            {start.isPending ? "Starting…" : "Start work"}
          </Button>
        )}
        {inProgress && (
          <>
            <Button size="sm" variant="outline" onClick={() => setCoOpen((o) => !o)}>
              {coOpen ? "Cancel" : "Report extra work"}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setDoneOpen((o) => !o)}>
              {doneOpen ? "Cancel" : "Mark work complete"}
            </Button>
          </>
        )}
        {bid.isSuccess && <span className="self-center text-sm text-[var(--success)]">Bid submitted ✓</span>}
        {changeOrder.isSuccess && (
          <span className="self-center text-sm text-[var(--success)]">Change order submitted ✓</span>
        )}
        {completion.isSuccess && (
          <span className="self-center text-sm text-[var(--success)]">Completion submitted ✓</span>
        )}
      </div>

      {/* Only success was ever shown, so a rejected bid looked like a button that did nothing. */}
      {(bid.error || changeOrder.error || completion.error) && (
        <p className="mt-2 text-sm text-destructive">
          {(bid.error as ApiError | null)?.message ??
            (changeOrder.error as ApiError | null)?.message ??
            (completion.error as ApiError | null)?.message}
        </p>
      )}

      {doneOpen && (
        <form onSubmit={submitCompletion} className="mt-3 space-y-2 border-t pt-3">
          <Label className="text-xs">
            Completion proof — the customer reviews this before you&apos;re paid
          </Label>
          <Input
            value={summary}
            onChange={(e) => setSummary(e.target.value)}
            placeholder="What did you do? e.g. Replaced P-trap and supply line, pressure tested"
            required
          />
          <div className="grid gap-2 sm:grid-cols-2">
            <Input value={materialsUsed} onChange={(e) => setMaterialsUsed(e.target.value)} placeholder="materials used" />
            <Input value={warranty} onChange={(e) => setWarranty(e.target.value)} placeholder="warranty (e.g. 90-day)" />
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Before photos</Label>
              <input
                type="file"
                accept="image/*"
                multiple
                onChange={(e) => setBeforeFiles(Array.from(e.target.files ?? []))}
                className="block w-full text-xs text-muted-foreground file:mr-2 file:rounded-md file:border file:border-input file:bg-background file:px-2 file:py-1 file:text-xs"
              />
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">After photos</Label>
              <input
                type="file"
                accept="image/*"
                multiple
                onChange={(e) => setAfterFiles(Array.from(e.target.files ?? []))}
                className="block w-full text-xs text-muted-foreground file:mr-2 file:rounded-md file:border file:border-input file:bg-background file:px-2 file:py-1 file:text-xs"
              />
            </div>
          </div>
          {(beforeFiles.length > 0 || afterFiles.length > 0) && (
            <p className="text-xs text-muted-foreground">
              {beforeFiles.length} before · {afterFiles.length} after
            </p>
          )}
          <Button type="submit" size="sm" disabled={uploading || completion.isPending || !summary}>
            {uploading ? "Uploading photos…" : completion.isPending ? "Submitting…" : "Submit completion"}
          </Button>
        </form>
      )}

      {coOpen && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            changeOrder.mutate(
              { description: coDesc, addedNetCents: coNet * 100, addedDays: coDays || undefined },
              { onSuccess: () => setCoOpen(false) },
            );
          }}
          className="mt-3 space-y-2 border-t pt-3"
        >
          <Label className="text-xs">Describe the newly discovered work (confidential net)</Label>
          <Input value={coDesc} onChange={(e) => setCoDesc(e.target.value)} placeholder="e.g. Corroded shutoff valve behind wall" required />
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label className="text-xs">Added net ($)</Label>
              <Input type="number" min={1} value={coNet} onChange={(e) => setCoNet(+e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Added days</Label>
              <Input type="number" min={0} value={coDays} onChange={(e) => setCoDays(+e.target.value)} />
            </div>
          </div>
          <Button type="submit" size="sm" disabled={changeOrder.isPending || !coDesc || coNet < 1}>
            {changeOrder.isPending ? "Submitting…" : "Submit change order"}
          </Button>
        </form>
      )}

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
