"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { RequireRole } from "@/components/require-auth";
import { useProperties, useReportIssue } from "@/lib/hooks";
import { ApiError, uploadFile } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { UrgencyBadge } from "@/components/status-badge";
import { DispatchQuoteCard } from "@/components/dispatch-quote";
import { formatRange } from "@/lib/utils";
import type { JobDetail } from "@/lib/types";

export default function ReportPage() {
  const router = useRouter();
  const { data: properties } = useProperties();
  const report = useReportIssue();
  const [propertyId, setPropertyId] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<JobDetail | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setUploading(true);
    try {
      const mediaKeys: string[] = [];
      for (const f of files) mediaKeys.push(await uploadFile(f));
      report.mutate({ propertyId, title, description, mediaKeys }, { onSuccess: setResult });
    } finally {
      setUploading(false);
    }
  }

  const busy = uploading || report.isPending;

  return (
    <RequireRole role="customer">
      <div className="mx-auto max-w-2xl space-y-6 px-4 py-8">
        <h1 className="text-2xl font-bold">Report an issue</h1>

        {!result ? (
          <Card>
            <CardHeader>
              <CardTitle>Describe the problem</CardTitle>
              <CardDescription>
                Add a short description. Our AI organizes it and shows a preliminary service range.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={submit} className="space-y-4">
                <div className="space-y-1.5">
                  <Label htmlFor="property">Property</Label>
                  <select
                    id="property"
                    required
                    value={propertyId}
                    onChange={(e) => setPropertyId(e.target.value)}
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <option value="" disabled>
                      Select a property…
                    </option>
                    {(properties ?? []).map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.line1} {p.city ? `— ${p.city}` : ""}
                      </option>
                    ))}
                  </select>
                  {/* A new account has no properties, and the field is required — so without this
                      the form simply cannot be submitted. It previously said so in grey helper
                      text with no way to act on it, which reads as "the page is broken" rather
                      than "one thing is missing". */}
                  {(properties ?? []).length === 0 && (
                    <div
                      className="mt-2 rounded-md border-l-2 p-3"
                      style={{ borderColor: "var(--warning)", background: "var(--card)" }}
                    >
                      <p className="text-sm font-medium">Add a property first</p>
                      <p className="mt-0.5 text-sm text-muted-foreground">
                        A repair has to belong to an address. Add yours and come straight back.
                      </p>
                      <Link href="/customer" className="mt-2 inline-block">
                        <Button size="sm" type="button">Add a property →</Button>
                      </Link>
                    </div>
                  )}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="title">Title</Label>
                  <Input id="title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Leak under kitchen sink" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="description">What&apos;s happening?</Label>
                  <Textarea
                    id="description"
                    required
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="Describe the issue. Mention anything about water, electrical, gas or smoke."
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="media">Photos or video (optional)</Label>
                  <input
                    id="media"
                    type="file"
                    accept="image/*,video/mp4"
                    multiple
                    onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
                    className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-background file:px-3 file:py-1.5 file:text-sm"
                  />
                  {files.length > 0 && (
                    <p className="text-xs text-muted-foreground">{files.length} file(s) selected</p>
                  )}
                </div>
                {report.isError && (
                  <p className="text-sm text-destructive">
                    {(report.error as ApiError)?.message ?? "Something went wrong. Please try again."}
                  </p>
                )}
                <Button type="submit" disabled={busy || !propertyId}>
                  {uploading ? "Uploading…" : report.isPending ? "Analyzing…" : "Get assessment"}
                </Button>
              </form>
            </CardContent>
          </Card>
        ) : (
          <AssessmentResult job={result} onContinue={() => router.push(`/customer/jobs/${result.id}`)} />
        )}
      </div>
    </RequireRole>
  );
}

function AssessmentResult({ job, onContinue }: { job: JobDetail; onContinue: () => void }) {
  const a = job.assessment;
  const e = job.estimate;
  return (
    <div className="space-y-4">
      {a && (
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="capitalize">{a.category}</CardTitle>
              <UrgencyBadge urgency={a.urgency} />
            </div>
            <CardDescription>{a.summary}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            {a.immediateSafetySteps.length > 0 && (
              <div className="rounded-md border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_12%,transparent)] p-3">
                <p className="font-medium text-[var(--warning)]">Immediate safety steps</p>
                <ul className="mt-1 list-inside list-disc text-foreground">
                  {a.immediateSafetySteps.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              </div>
            )}
            <p>
              {a.professionalRequired
                ? "A verified professional is recommended for this issue."
                : a.safeDiyAllowed
                  ? "This may be safe to DIY — or request a professional any time."
                  : "A professional assessment is recommended."}
            </p>
            <p className="text-xs text-muted-foreground">{a.disclaimer}</p>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Estimated service range</CardTitle>
          <CardDescription>{e?.message}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-3xl font-bold">
            {e?.priceAvailable ? formatRange(e.retailLowCents, e.retailHighCents) : "On-site assessment required"}
          </p>
          <p className="text-xs text-muted-foreground">{e?.disclaimer}</p>
        </CardContent>
      </Card>

      <ChooseRoute job={job} onDispatch={onContinue} />
    </div>
  );
}

/**
 * DIY or professional, and — if professional — what the visit will cost before anyone is sent.
 *
 * <p>The visit fee belongs here rather than on the dispatch screen. By the time a job reaches
 * dispatch the homeowner has already committed; a fee first seen at that point is a fee they were
 * told about rather than one they agreed to.
 *
 * <p>DIY is only offered when the assessment actually cleared it. A job the safety gate refused is
 * not presented as a choice, because offering it invites the attempt.
 */
function ChooseRoute({ job, onDispatch }: { job: JobDetail; onDispatch: () => void }) {
  const [route, setRoute] = useState<"undecided" | "professional">("undecided");
  const a = job.assessment;
  const diyAllowed = Boolean(a?.safeDiyAllowed) && !a?.professionalRequired;

  if (route === "professional") {
    return (
      <DispatchQuoteCard
        jobId={job.id}
        trade={a?.recommendedTrade ?? a?.category ?? null}
        emergency={a?.urgency === "emergency"}
        onAccept={onDispatch}
      />
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>How would you like to proceed?</CardTitle>
        <CardDescription>
          {diyAllowed
            ? "This one looks safe to attempt yourself — or we can send a professional."
            : "This needs a qualified professional."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        {diyAllowed && (
          <Link href="/customer/assistant">
            <Button variant="outline">Guide me through it</Button>
          </Link>
        )}
        <Button onClick={() => setRoute("professional")}>Request a professional</Button>
      </CardContent>
    </Card>
  );
}
