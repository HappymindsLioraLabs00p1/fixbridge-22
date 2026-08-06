"use client";

import Link from "next/link";
import { RequireAuth } from "@/components/require-auth";
import { useNotifications } from "@/lib/hooks";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { NotificationItem } from "@/lib/types";

const LABELS: Record<string, string> = {
  contractor_invited: "New job invitation",
  proposal_sent: "Your proposal is ready",
  change_order_sent: "Additional work needs your approval",
  work_completed: "Your job is complete",
  payout_released: "Payout released",
};

function label(template: string) {
  return LABELS[template] ?? template.replaceAll("_", " ");
}

export default function NotificationsPage() {
  return (
    <RequireAuth>
      <div className="mx-auto max-w-2xl space-y-6 px-4 py-8">
        <h1 className="text-2xl font-bold">Activity</h1>
        <Feed />
      </div>
    </RequireAuth>
  );
}

function Feed() {
  const { data: items, isLoading } = useNotifications();
  if (isLoading) return <p className="text-sm text-muted-foreground">Loading…</p>;
  if (!items || items.length === 0) {
    return <p className="text-sm text-muted-foreground">No notifications yet.</p>;
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Recent notifications</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {items.map((n: NotificationItem, i: number) => (
          <div key={i} className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm">
            <div>
              <p className="font-medium">{label(n.template)}</p>
              <p className="text-xs text-muted-foreground">
                {new Date(n.createdAt).toLocaleString()}
                {n.jobId && n.jobId !== "null" ? (
                  <>
                    {" · "}
                    <Link href={`/customer/jobs/${n.jobId}`} className="text-primary hover:underline">
                      view job
                    </Link>
                  </>
                ) : null}
              </p>
            </div>
            <Badge variant="outline">{n.channel}</Badge>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
