"use client";

import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import {
  useAdminChangeOrders,
  useBidOptions,
  useContractorOptions,
  useCreateProposal,
  useDispatchQueue,
  useInviteContractor,
  useJobPayments,
  usePublishChangeOrder,
  useRefundPayment,
  useReleasePayout,
  useSetPayoutHold,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { JobStatusBadge, UrgencyBadge } from "@/components/status-badge";
import { formatCents, formatRange } from "@/lib/utils";
import type { AdminJob } from "@/lib/types";

export default function AdminDashboard() {
  const { data: jobs, isLoading } = useDispatchQueue();
  return (
    <RequireRole role="admin">
      <div className="mx-auto max-w-5xl space-y-6 px-4 py-8">
        <div>
          <h1 className="text-2xl font-bold">Dispatch console</h1>
          <p className="text-sm text-muted-foreground">
            You are the only role that sees both the contractor net and the customer retail — and the margin.
          </p>
        </div>

        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : jobs && jobs.length > 0 ? (
          <div className="space-y-4">
            {jobs.map((job) => (
              <AdminJobCard key={job.jobId} job={job} />
            ))}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">No jobs are awaiting dispatch.</p>
        )}
      </div>
    </RequireRole>
  );
}

function AdminJobCard({ job }: { job: AdminJob }) {
  const invite = useInviteContractor(job.jobId);
  const proposal = useCreateProposal(job.jobId);
  const payout = useReleasePayout(job.jobId);
  const { data: contractorOptions } = useContractorOptions();
  const { data: bidOptions } = useBidOptions(job.jobId);
  const [contractorId, setContractorId] = useState("");
  const [bidId, setBidId] = useState("");
  const [scope, setScope] = useState("");
  const selectedBid = (bidOptions ?? []).find((b) => b.bidId === bidId);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="capitalize">{job.category ?? job.title ?? "Job"}</CardTitle>
          <div className="flex items-center gap-2">
            <UrgencyBadge urgency={job.urgency} />
            <JobStatusBadge status={job.status} />
          </div>
        </div>
        <CardDescription className="font-mono text-xs">{job.jobId}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-md border p-3">
            <p className="text-muted-foreground">Est. contractor net (internal)</p>
            <p className="font-semibold">{formatRange(job.estContractorNetLow, job.estContractorNetHigh)}</p>
          </div>
          <div className="rounded-md border p-3">
            <p className="text-muted-foreground">Customer retail range</p>
            <p className="font-semibold">{formatRange(job.customerRetailLow, job.customerRetailHigh)}</p>
          </div>
        </div>

        <div className="grid gap-3 border-t pt-4 sm:grid-cols-3">
          {/* Invite */}
          <div className="space-y-1.5">
            <Label className="text-xs">Invite contractor</Label>
            <Select value={contractorId} onChange={(e) => setContractorId(e.target.value)}>
              <option value="">Select a contractor…</option>
              {(contractorOptions ?? []).map((c) => (
                <option key={c.id} value={c.id} disabled={!c.eligible}>
                  {c.businessName}
                  {c.eligible ? "" : ` — ${c.ineligibleReason}`}
                </option>
              ))}
            </Select>
            {contractorOptions && contractorOptions.length === 0 && (
              <p className="text-xs text-muted-foreground">No contractors have registered yet.</p>
            )}
            <Button size="sm" variant="outline" disabled={invite.isPending || !contractorId} onClick={() => invite.mutate(contractorId)}>
              {invite.isPending ? "Inviting…" : "Invite"}
            </Button>
            {invite.isSuccess && <p className="text-xs text-[var(--success)]">Invited ✓</p>}
          </div>

          {/* Create proposal */}
          <div className="space-y-1.5">
            <Label className="text-xs">Create proposal from bid</Label>
            <Select value={bidId} onChange={(e) => setBidId(e.target.value)}>
              <option value="">
                {bidOptions && bidOptions.length === 0 ? "No bids submitted yet" : "Select a bid…"}
              </option>
              {(bidOptions ?? []).map((b) => (
                <option key={b.bidId} value={b.bidId}>
                  {b.contractorName} — net {formatCents(b.netTotalCents)} → retail{" "}
                  {formatCents(b.previewRetailCents)}
                </option>
              ))}
            </Select>
            {selectedBid && (
              <p className="text-xs text-muted-foreground">
                Margin if accepted: <span className="font-medium">{formatCents(selectedBid.previewMarginCents)}</span>
                {selectedBid.durationDays ? ` · ~${selectedBid.durationDays} day(s)` : ""}
              </p>
            )}
            <Input value={scope} onChange={(e) => setScope(e.target.value)} placeholder="scope (optional)" />
            <Button
              size="sm"
              variant="outline"
              disabled={proposal.isPending || !bidId}
              onClick={() => proposal.mutate({ bidId, scope })}
            >
              Generate retail proposal
            </Button>
            {proposal.data && (
              <p className="text-xs text-muted-foreground">
                Net {formatCents(proposal.data.contractorNetCents)} → Retail {formatCents(proposal.data.retailTotalCents)} ·
                margin <span className="font-medium">{formatCents(proposal.data.marginCents)}</span>
              </p>
            )}
          </div>

          {/* Payout */}
          <div className="space-y-1.5">
            <Label className="text-xs">Release payout</Label>
            <p className="text-xs text-muted-foreground">Only after completion is approved.</p>
            <Button size="sm" disabled={payout.isPending} onClick={() => payout.mutate()}>
              Release contractor payout
            </Button>
            {payout.isSuccess && <p className="text-xs text-[var(--success)]">Payout released ✓</p>}
          </div>
        </div>

        <AdminChangeOrders jobId={job.jobId} />
        <AdminMoneyControls jobId={job.jobId} />
      </CardContent>
    </Card>
  );
}

function AdminMoneyControls({ jobId }: { jobId: string }) {
  const { data: payments } = useJobPayments(jobId);
  const refund = useRefundPayment(jobId);
  const hold = useSetPayoutHold(jobId);
  const [target, setTarget] = useState("");
  const [amount, setAmount] = useState(0);
  const [reason, setReason] = useState("");
  const [holdReason, setHoldReason] = useState("");

  if (!payments || payments.length === 0) return null;
  const selected = payments.find((p) => p.id === target);

  return (
    <div className="space-y-3 border-t pt-4">
      <p className="text-sm font-medium">Payments &amp; refunds</p>

      {payments.map((p) => (
        <div key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
          <span className="capitalize">{p.type.replaceAll("_", " ")}</span>
          <span className="text-muted-foreground">
            {formatCents(p.amountCents)}
            {p.refundedCents > 0 && <> · refunded {formatCents(p.refundedCents)}</>}
            {" · "}refundable <span className="font-medium">{formatCents(p.refundableCents)}</span>
          </span>
          <span className="flex items-center gap-2">
            {p.disputed && <span className="rounded-full bg-destructive px-2 py-0.5 text-xs text-destructive-foreground">disputed</span>}
            <span className="capitalize text-muted-foreground">{p.status}</span>
          </span>
        </div>
      ))}

      <div className="grid gap-3 sm:grid-cols-2">
        {/* Refund */}
        <div className="space-y-1.5 rounded-md border p-3">
          <Label className="text-xs">Refund a payment</Label>
          <Select value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">Select a payment…</option>
            {payments
              .filter((p) => p.refundableCents > 0)
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.type.replaceAll("_", " ")} — up to {formatCents(p.refundableCents)}
                </option>
              ))}
          </Select>
          <Input
            type="number"
            min={1}
            placeholder="amount ($)"
            value={amount || ""}
            onChange={(e) => setAmount(+e.target.value)}
          />
          <Input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Button
            size="sm"
            variant="outline"
            disabled={refund.isPending || !target || amount <= 0}
            onClick={() => refund.mutate({ paymentId: target, amountCents: Math.round(amount * 100), reason })}
          >
            {refund.isPending ? "Refunding…" : "Issue refund"}
          </Button>
          {selected && amount * 100 > selected.refundableCents && (
            <p className="text-xs text-destructive">Exceeds refundable {formatCents(selected.refundableCents)}</p>
          )}
          {refund.isSuccess && <p className="text-xs text-[var(--success)]">Refund issued ✓</p>}
          {refund.isError && <p className="text-xs text-destructive">{(refund.error as Error)?.message}</p>}
        </div>

        {/* Payout hold */}
        <div className="space-y-1.5 rounded-md border p-3">
          <Label className="text-xs">Payout hold</Label>
          <p className="text-xs text-muted-foreground">Blocks the contractor payout until lifted.</p>
          <Input placeholder="reason for hold" value={holdReason} onChange={(e) => setHoldReason(e.target.value)} />
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={hold.isPending || !holdReason} onClick={() => hold.mutate(holdReason)}>
              Hold payout
            </Button>
            <Button size="sm" variant="ghost" disabled={hold.isPending} onClick={() => hold.mutate(null)}>
              Lift hold
            </Button>
          </div>
          {hold.data && (
            <p className="text-xs text-muted-foreground">
              {hold.data.held ? `On hold: ${hold.data.reason}` : "No hold in place"}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

function AdminChangeOrders({ jobId }: { jobId: string }) {
  const { data: orders } = useAdminChangeOrders(jobId);
  const publish = usePublishChangeOrder(jobId);
  if (!orders || orders.length === 0) return null;
  return (
    <div className="space-y-2 border-t pt-4">
      <p className="text-sm font-medium">Change orders</p>
      {orders.map((co) => (
        <div key={co.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm">
          <span className="max-w-sm">{co.description}</span>
          <span className="text-muted-foreground">
            net {formatCents(co.addedNetCents)} → retail {formatCents(co.addedRetailCents)} · margin{" "}
            <span className="font-medium">{formatCents(co.marginCents)}</span>
          </span>
          {co.status === "draft" ? (
            <Button size="sm" variant="outline" disabled={publish.isPending} onClick={() => publish.mutate(co.id)}>
              Price &amp; send
            </Button>
          ) : (
            <span className="capitalize text-muted-foreground">{co.status}</span>
          )}
        </div>
      ))}
    </div>
  );
}
