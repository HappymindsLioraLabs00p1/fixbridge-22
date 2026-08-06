"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import {
  useApproveChangeOrder,
  useApproveProposal,
  useChangeOrders,
  useDispatchCheckout,
  useJob,
  useProposals,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { JobStatusBadge, UrgencyBadge } from "@/components/status-badge";
import { formatCents, formatRange } from "@/lib/utils";
import type { CheckoutView } from "@/lib/types";

const SERVICE_OPTIONS = [
  { value: "weekday_scheduled", label: "Scheduled weekday visit" },
  { value: "same_day_priority", label: "Same-day priority visit" },
  { value: "evening_weekend", label: "Evening / weekend visit" },
];

export default function JobDetailPage() {
  const params = useParams<{ id: string }>();
  const jobId = params.id;
  const { data: job, isLoading } = useJob(jobId);
  const { data: proposals } = useProposals(jobId);
  const dispatch = useDispatchCheckout(jobId);
  const approve = useApproveProposal(jobId);
  const [serviceType, setServiceType] = useState(SERVICE_OPTIONS[0].value);
  const [checkout, setCheckout] = useState<CheckoutView | null>(null);

  return (
    <RequireRole role="customer">
      <div className="mx-auto max-w-2xl space-y-6 px-4 py-8">
        {isLoading || !job ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <>
            <div className="flex items-center justify-between">
              <h1 className="text-2xl font-bold">{job.title ?? "Request"}</h1>
              <JobStatusBadge status={job.status} />
            </div>

            {job.assessment && (
              <Card>
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <CardTitle className="capitalize">{job.assessment.category}</CardTitle>
                    <UrgencyBadge urgency={job.assessment.urgency} />
                  </div>
                  <CardDescription>{job.assessment.summary}</CardDescription>
                </CardHeader>
              </Card>
            )}

            {job.media && job.media.length > 0 && (
              <div className="grid grid-cols-3 gap-2">
                {job.media.map((m, i) =>
                  m.mediaType === "video" ? (
                    <video key={i} src={m.url} controls className="w-full rounded-md border" />
                  ) : (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      key={i}
                      src={m.url}
                      alt="attachment"
                      className="aspect-square w-full rounded-md border object-cover"
                    />
                  ),
                )}
              </div>
            )}

            {job.estimate && (
              <Card>
                <CardHeader>
                  <CardTitle>Estimated service range</CardTitle>
                  <CardDescription>{job.estimate.message}</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-2xl font-bold">
                    {job.estimate.priceAvailable
                      ? formatRange(job.estimate.retailLowCents, job.estimate.retailHighCents)
                      : "On-site assessment required"}
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground">{job.estimate.disclaimer}</p>
                </CardContent>
              </Card>
            )}

            {job.status === "awaiting_service_payment" && (
              <Card>
                <CardHeader>
                  <CardTitle>Choose a preferred service time</CardTitle>
                  <CardDescription>
                    Pay the Service Assessment &amp; Dispatch fee to have a verified pro dispatched. This is
                    your <em>preferred</em> time until a contractor accepts.
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-3">
                  <select
                    value={serviceType}
                    onChange={(e) => setServiceType(e.target.value)}
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                  >
                    {SERVICE_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <Button
                    disabled={dispatch.isPending}
                    onClick={() => dispatch.mutate(serviceType, { onSuccess: setCheckout })}
                  >
                    {dispatch.isPending ? "Preparing checkout…" : "Pay dispatch fee"}
                  </Button>
                  {checkout && <CheckoutNotice checkout={checkout} />}
                </CardContent>
              </Card>
            )}

            {proposals && proposals.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>Proposal</CardTitle>
                  <CardDescription>One clear retail price from us.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  {proposals.map((p) => (
                    <div key={p.proposalId} className="space-y-2 rounded-md border p-4">
                      {p.scope && <p className="text-sm">{p.scope}</p>}
                      <p className="text-2xl font-bold">{formatCents(p.retailTotalCents)}</p>
                      {p.timeline && <p className="text-sm text-muted-foreground">Timeline: {p.timeline}</p>}
                      {p.status === "sent" ? (
                        <Button
                          disabled={approve.isPending}
                          onClick={() => approve.mutate(p.proposalId, { onSuccess: setCheckout })}
                        >
                          {approve.isPending ? "Preparing checkout…" : "Approve & pay"}
                        </Button>
                      ) : (
                        <p className="text-sm font-medium capitalize text-muted-foreground">{p.status}</p>
                      )}
                    </div>
                  ))}
                  {checkout && <CheckoutNotice checkout={checkout} />}
                </CardContent>
              </Card>
            )}

            <ChangeOrdersCard jobId={jobId} />
          </>
        )}
      </div>
    </RequireRole>
  );
}

function ChangeOrdersCard({ jobId }: { jobId: string }) {
  const { data: changeOrders } = useChangeOrders(jobId);
  const approve = useApproveChangeOrder(jobId);
  if (!changeOrders || changeOrders.length === 0) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Additional work</CardTitle>
        <CardDescription>
          Extra work discovered on site. Work pauses until you approve the added price.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {changeOrders.map((co) => (
          <div key={co.id} className="space-y-2 rounded-md border p-4">
            <p className="text-sm">{co.description}</p>
            <p className="text-xl font-bold">+{formatCents(co.addedRetailCents)}</p>
            {co.addedDays != null && (
              <p className="text-sm text-muted-foreground">Adds ~{co.addedDays} day(s)</p>
            )}
            {co.status === "sent" ? (
              <Button size="sm" disabled={approve.isPending} onClick={() => approve.mutate(co.id)}>
                {approve.isPending ? "Approving…" : "Approve added work"}
              </Button>
            ) : (
              <p className="text-sm font-medium capitalize text-muted-foreground">{co.status}</p>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/**
 * In production this would redirect to Stripe Checkout. In the frontend-first stub, Stripe isn't live,
 * so we surface the session details instead of navigating to a dead URL.
 */
function CheckoutNotice({ checkout }: { checkout: CheckoutView }) {
  return (
    <div className="rounded-md border bg-muted p-3 text-sm">
      <p className="font-medium">Checkout ready — {formatCents(checkout.amountCents)}</p>
      <p className="mt-1 break-all text-xs text-muted-foreground">
        Redirect target: {checkout.url}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        (Live mode redirects to Stripe; the payment webhook then advances this job.)
      </p>
    </div>
  );
}
