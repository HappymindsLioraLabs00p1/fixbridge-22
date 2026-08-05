"use client";

import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import {
  useCreateProposal,
  useDispatchQueue,
  useInviteContractor,
  useReleasePayout,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
  const [contractorId, setContractorId] = useState("");
  const [bidId, setBidId] = useState("");
  const [scope, setScope] = useState("");

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
            <Label className="text-xs">Invite contractor (id)</Label>
            <Input value={contractorId} onChange={(e) => setContractorId(e.target.value)} placeholder="contractor uuid" />
            <Button size="sm" variant="outline" disabled={invite.isPending || !contractorId} onClick={() => invite.mutate(contractorId)}>
              Invite
            </Button>
          </div>

          {/* Create proposal */}
          <div className="space-y-1.5">
            <Label className="text-xs">Create proposal from bid (id)</Label>
            <Input value={bidId} onChange={(e) => setBidId(e.target.value)} placeholder="bid uuid" />
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
      </CardContent>
    </Card>
  );
}
