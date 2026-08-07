"use client";

import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import {
  useContractorCompliance,
  useContractorOptions,
  useReviewDocument,
  useSetSuspension,
} from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function AdminCompliancePage() {
  const { data: contractors } = useContractorOptions();
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <RequireRole role="admin">
      <div className="mx-auto max-w-5xl space-y-6 px-4 py-8">
        <div>
          <h1 className="text-2xl font-bold">Contractor compliance</h1>
          <p className="text-sm text-muted-foreground">
            Verify licences and insurance. Nobody can be dispatched until their required documents are
            checked and unexpired.
          </p>
        </div>

        <div className="grid gap-6 md:grid-cols-[280px_1fr]">
          {/* Contractor list */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Contractors</CardTitle>
              <CardDescription>{contractors?.length ?? 0} registered</CardDescription>
            </CardHeader>
            <CardContent className="space-y-1.5">
              {(contractors ?? []).map((c) => (
                <button
                  key={c.id}
                  onClick={() => setSelected(c.id)}
                  className={`w-full rounded-md border p-2.5 text-left text-sm transition-colors hover:bg-muted ${
                    selected === c.id ? "border-primary bg-muted" : ""
                  }`}
                >
                  <span className="block font-medium">{c.businessName}</span>
                  <span
                    className="text-xs"
                    style={{ color: c.eligible ? "var(--success)" : "var(--warning)" }}
                  >
                    {c.eligible ? "Cleared for dispatch" : c.ineligibleReason}
                  </span>
                </button>
              ))}
              {contractors?.length === 0 && (
                <p className="text-sm text-muted-foreground">No contractors have registered yet.</p>
              )}
            </CardContent>
          </Card>

          {/* Document review */}
          {selected ? (
            <ComplianceDetail contractorId={selected} />
          ) : (
            <Card>
              <CardContent className="p-6">
                <p className="text-sm text-muted-foreground">
                  Select a contractor to review their documents.
                </p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </RequireRole>
  );
}

function ComplianceDetail({ contractorId }: { contractorId: string }) {
  const { data: compliance, isLoading } = useContractorCompliance(contractorId);
  const review = useReviewDocument(contractorId);
  const suspend = useSetSuspension(contractorId);
  const [reason, setReason] = useState("");

  if (isLoading) return <p className="text-sm text-muted-foreground">Loading…</p>;
  if (!compliance) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Documents</CardTitle>
        <CardDescription>
          {compliance.compliant
            ? "All required documents are verified and current."
            : `Blocking dispatch: ${[...compliance.missingOrUnverified, ...compliance.expired]
                .map((k) => k.replace("_", " "))
                .join(", ")}`}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {compliance.documents.length === 0 && (
          <p className="text-sm text-muted-foreground">Nothing submitted yet.</p>
        )}

        {compliance.documents.map((d) => (
          <div key={d.id} className="space-y-2 rounded-md border p-3">
            <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span className="font-medium capitalize">{d.kind.replace("_", " ")}</span>
              <span className="text-muted-foreground">
                {d.number ? `#${d.number}` : "no number"}
                {d.jurisdiction && ` · ${d.jurisdiction}`}
                {d.expiresOn && ` · expires ${d.expiresOn}`}
              </span>
              <span
                className="rounded-full px-2 py-0.5 text-xs capitalize"
                style={{
                  background:
                    d.status === "valid"
                      ? "var(--success)"
                      : d.status === "pending"
                        ? "var(--muted)"
                        : "var(--destructive)",
                  color: d.status === "pending" ? "var(--muted-foreground)" : "#fff",
                }}
              >
                {d.status}
              </span>
            </div>

            {d.fileUrl && (
              <a href={d.fileUrl} target="_blank" rel="noopener noreferrer" className="text-xs text-primary underline">
                View document ↗
              </a>
            )}

            {d.status === "pending" && (
              <div className="flex gap-2">
                <Button
                  size="sm"
                  disabled={review.isPending}
                  onClick={() => review.mutate({ documentId: d.id, approve: true, note: "verified" })}
                >
                  Verify
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={review.isPending}
                  onClick={() => review.mutate({ documentId: d.id, approve: false, note: "rejected" })}
                >
                  Reject
                </Button>
              </div>
            )}
          </div>
        ))}

        <div className="space-y-2 border-t pt-4">
          <p className="text-sm font-medium">Suspension</p>
          <p className="text-xs text-muted-foreground">
            Suspending removes them from dispatch immediately, whatever their paperwork says.
          </p>
          <Input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="destructive"
              disabled={suspend.isPending || !reason}
              onClick={() => suspend.mutate({ suspended: true, reason })}
            >
              Suspend
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={suspend.isPending}
              onClick={() => suspend.mutate({ suspended: false, reason: "reinstated" })}
            >
              Reinstate
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
