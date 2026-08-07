"use client";

import Link from "next/link";
import { RequireRole } from "@/components/require-auth";
import { useReportOverview } from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCents } from "@/lib/utils";

const STAGE_LABEL: Record<string, string> = {
  awaiting_service_payment: "Awaiting payment",
  awaiting_contractor: "Awaiting contractor",
  bid_received: "Bid received",
  proposal_sent: "Proposal sent",
  scheduled: "Scheduled",
  work_completed: "Work completed",
  paid_out: "Paid out",
};

export default function AdminReportsPage() {
  const { data, isLoading } = useReportOverview();

  return (
    <RequireRole role="admin">
      <div className="mx-auto max-w-5xl space-y-6 px-4 py-8">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold">Reporting</h1>
            <p className="text-sm text-muted-foreground">
              Revenue, margin and delivery. These figures are admin-only — customers and contractors
              never see the margin.
            </p>
          </div>
          <Link href="/admin">
            <Button variant="outline" size="sm">
              Dispatch console
            </Button>
          </Link>
        </div>

        {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}

        {data && (
          <>
            {/* Headline figures — a number is the right form here, not a chart. */}
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Net revenue" value={formatCents(data.netRevenueCents)} sub={`${formatCents(data.collectedCents)} collected · ${formatCents(data.refundedCents)} refunded`} />
              <Stat label="Contractor payouts" value={formatCents(data.contractorPayoutsCents)} sub={`${data.jobsCompleted} job(s) paid out`} />
              <Stat
                label="Gross profit"
                value={formatCents(data.grossProfitCents)}
                sub={`${data.grossMarginPercent.toFixed(1)}% margin`}
                accent
              />
              <Stat
                label="Conversion"
                value={`${data.conversionPercent.toFixed(0)}%`}
                sub={`${data.jobsCompleted} of ${data.jobsReported} reported issues`}
              />
            </div>

            {/* Funnel — one series, so no legend; the title names it. */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Jobs by stage</CardTitle>
                <CardDescription>Where work is sitting right now.</CardDescription>
              </CardHeader>
              <CardContent>
                <Funnel funnel={data.funnel} />
              </CardContent>
            </Card>

            {/* Contractor performance */}
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Contractor performance</CardTitle>
                <CardDescription>Ranked by what they&apos;ve earned.</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[520px] text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <Th>Contractor</Th>
                        <Th>Status</Th>
                        <Th right>Bids</Th>
                        <Th right>Jobs paid</Th>
                        <Th right>Earned</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.contractors.map((c) => (
                        <tr key={c.contractorId} className="border-b last:border-0">
                          <td className="py-2.5 pr-3 font-medium">{c.businessName}</td>
                          <td className="py-2.5 pr-3 capitalize text-muted-foreground">
                            {c.status.replace("_", " ")}
                          </td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{c.bidsSubmitted}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{c.jobsPaidOut}</td>
                          <td className="py-2.5 text-right font-medium tabular-nums">
                            {formatCents(c.totalEarnedCents)}
                          </td>
                        </tr>
                      ))}
                      {data.contractors.length === 0 && (
                        <tr>
                          <td colSpan={5} className="py-4 text-muted-foreground">
                            No contractors yet.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </CardContent>
            </Card>
          </>
        )}
      </div>
    </RequireRole>
  );
}

function Stat({ label, value, sub, accent }: { label: string; value: string; sub?: string; accent?: boolean }) {
  return (
    <div className="rounded-lg border bg-card p-4">
      <p className="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">{label}</p>
      <p
        className="mt-1.5 text-2xl font-bold tabular-nums"
        style={accent ? { color: "var(--primary)" } : undefined}
      >
        {value}
      </p>
      {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
    </div>
  );
}

function Funnel({ funnel }: { funnel: Record<string, number> }) {
  const entries = Object.entries(funnel);
  const max = Math.max(1, ...entries.map(([, v]) => v));

  return (
    <div className="space-y-2.5">
      {entries.map(([stage, count]) => (
        <div key={stage} className="grid grid-cols-[150px_1fr_40px] items-center gap-3">
          <span className="text-xs text-muted-foreground">{STAGE_LABEL[stage] ?? stage}</span>
          {/* Recessive track, 4px rounded data end anchored to the baseline. */}
          <div className="h-5 rounded-[4px] bg-muted">
            <div
              className="h-5 rounded-[4px] transition-[width] duration-500"
              style={{
                width: `${Math.max(count === 0 ? 0 : 4, (count / max) * 100)}%`,
                background: "var(--primary)",
              }}
              role="img"
              aria-label={`${STAGE_LABEL[stage] ?? stage}: ${count}`}
            />
          </div>
          <span className="text-right text-sm font-medium tabular-nums">{count}</span>
        </div>
      ))}
    </div>
  );
}

function Th({ children, right }: { children: React.ReactNode; right?: boolean }) {
  return (
    <th
      className={`pb-2 font-mono text-[11px] uppercase tracking-widest text-muted-foreground ${
        right ? "text-right pr-3" : "pr-3"
      }`}
    >
      {children}
    </th>
  );
}
