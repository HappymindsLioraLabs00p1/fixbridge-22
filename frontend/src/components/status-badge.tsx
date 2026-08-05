import { Badge } from "@/components/ui/badge";
import type { AiUrgency, JobStatus } from "@/lib/types";

const URGENCY_VARIANT: Record<AiUrgency, "secondary" | "warning" | "destructive"> = {
  low: "secondary",
  medium: "secondary",
  high: "warning",
  emergency: "destructive",
};

export function UrgencyBadge({ urgency }: { urgency: AiUrgency | null }) {
  if (!urgency) return null;
  return <Badge variant={URGENCY_VARIANT[urgency]}>{urgency}</Badge>;
}

/** Job status shown as a readable chip. */
export function JobStatusBadge({ status }: { status: JobStatus }) {
  const paidOut = status === "paid_out" || status === "closed";
  const problem = status === "canceled" || status === "refunded" || status === "disputed";
  return (
    <Badge variant={paidOut ? "success" : problem ? "destructive" : "outline"}>
      {status.replaceAll("_", " ")}
    </Badge>
  );
}
