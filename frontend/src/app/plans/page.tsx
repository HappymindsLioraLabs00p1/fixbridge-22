"use client";

import { useState } from "react";
import { RequireAuth } from "@/components/require-auth";
import { useCurrentSubscription, usePlans, useSubscribe } from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { BillingCheckout } from "@/lib/types";

export default function PlansPage() {
  return (
    <RequireAuth>
      <div className="mx-auto max-w-5xl space-y-6 px-4 py-8">
        <div>
          <h1 className="text-2xl font-bold">Membership plans</h1>
          <p className="text-sm text-muted-foreground">
            Recurring plans billed monthly through Stripe. Cancel anytime.
          </p>
        </div>
        <PlansGrid />
      </div>
    </RequireAuth>
  );
}

function PlansGrid() {
  const { data: plans, isLoading } = usePlans();
  const { data: current } = useCurrentSubscription();
  const subscribe = useSubscribe();
  const [checkout, setCheckout] = useState<BillingCheckout | null>(null);
  const [pendingPlan, setPendingPlan] = useState<string | null>(null);

  if (isLoading) return <p className="text-sm text-muted-foreground">Loading plans…</p>;

  return (
    <>
      {current && (
        <div className="rounded-md border bg-muted p-3 text-sm">
          Active plan: <span className="font-medium capitalize">{current.planCode.replaceAll("_", " ")}</span>{" "}
          <Badge variant="success">{current.status}</Badge>
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {(plans ?? []).map((plan) => {
          const isCurrent = current?.planCode === plan.code;
          return (
            <Card key={plan.code} className="flex flex-col">
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base">{plan.name}</CardTitle>
                  <Badge variant="outline">{plan.audience}</Badge>
                </div>
                <CardDescription>{plan.blurb}</CardDescription>
              </CardHeader>
              <CardContent className="mt-auto space-y-2">
                <p className="text-xs text-muted-foreground">Billed {plan.interval}</p>
                <Button
                  className="w-full"
                  disabled={!plan.available || isCurrent || (subscribe.isPending && pendingPlan === plan.code)}
                  onClick={() => {
                    setPendingPlan(plan.code);
                    subscribe.mutate(plan.code, {
                      onSuccess: (c) => {
                        // Live mode returns a Stripe Checkout URL to redirect to.
                        if (c.url?.startsWith("https://") && !c.url.includes("stub")) {
                          window.location.href = c.url;
                        } else {
                          setCheckout(c);
                        }
                      },
                    });
                  }}
                >
                  {isCurrent
                    ? "Current plan"
                    : !plan.available
                      ? "Coming soon"
                      : subscribe.isPending && pendingPlan === plan.code
                        ? "Starting…"
                        : "Subscribe"}
                </Button>
              </CardContent>
            </Card>
          );
        })}
      </div>
      {checkout && (
        <div className="rounded-md border bg-muted p-3 text-sm">
          <p className="font-medium">Subscription checkout ready</p>
          <p className="mt-1 break-all text-xs text-muted-foreground">Redirect target: {checkout.url}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            (Live mode redirects to Stripe Billing; the webhook then activates your plan.)
          </p>
        </div>
      )}
    </>
  );
}
