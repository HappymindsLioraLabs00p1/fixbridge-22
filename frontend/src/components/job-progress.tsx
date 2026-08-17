import type { JobStatus } from "@/lib/types";

/**
 * Where a job has got to, in the four steps a customer actually cares about.
 *
 * <p>The job model has twenty-six statuses because the platform needs that precision — bid_received
 * and proposal_sent are entirely different problems for an admin. A homeowner needs to know whether
 * someone is coming, whether they have arrived, and whether it is done. Collapsing the two is the
 * point of this component: it is a translation, not a simplification of the underlying state.
 */
const STEPS = ["Assigned", "On the way", "In progress", "Completed"] as const;

/** Index of the step a status sits in, or -1 when the job has not been dispatched yet. */
function stepFor(status: JobStatus): number {
  switch (status) {
    case "contractor_invited":
    case "contractor_accepted":
    case "awaiting_bid":
    case "bid_received":
    case "proposal_sent":
    case "awaiting_customer_approval":
    case "approved":
    case "scheduled":
      return 0;
    case "contractor_en_route":
      return 1;
    case "work_started":
    case "change_order_pending":
      return 2;
    case "work_completed":
    case "customer_review_pending":
    case "admin_review_pending":
    case "payout_pending":
    case "paid_out":
    case "closed":
      return 3;
    default:
      return -1;
  }
}

/** The line under the heading — what is happening, in the customer's words. */
export function progressCaption(status: JobStatus): string {
  switch (status) {
    case "contractor_en_route":
      return "Your expert is on the way";
    case "work_started":
      return "Your expert is working on it";
    case "change_order_pending":
      return "Extra work is waiting for your approval";
    case "work_completed":
    case "customer_review_pending":
      return "Finished — please confirm the work";
    case "admin_review_pending":
    case "payout_pending":
    case "paid_out":
    case "closed":
      return "Completed";
    default:
      return "Booked — we're lining up your expert";
  }
}

export function JobProgress({ status }: { status: JobStatus }) {
  const current = stepFor(status);
  if (current < 0) return null;

  return (
    <ol className="flex items-start" aria-label="Job progress">
      {STEPS.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <li key={label} className="flex flex-1 flex-col items-center gap-1.5 text-center">
            <div className="flex w-full items-center">
              {/* Connectors sit either side of the dot so the line meets it rather than the label. */}
              <span
                className={`h-0.5 flex-1 ${i === 0 ? "opacity-0" : done || active ? "bg-success" : "bg-border"}`}
              />
              <span
                aria-current={active ? "step" : undefined}
                className={`grid h-5 w-5 shrink-0 place-items-center rounded-full border-2 text-[10px] font-bold ${
                  done
                    ? "border-success bg-success text-white"
                    : active
                      ? "border-success bg-white text-success"
                      : "border-border bg-white text-muted-foreground"
                }`}
              >
                {done ? "✓" : active ? "●" : ""}
              </span>
              <span
                className={`h-0.5 flex-1 ${i === STEPS.length - 1 ? "opacity-0" : done ? "bg-success" : "bg-border"}`}
              />
            </div>
            <span
              className={`text-[11px] leading-tight ${
                active ? "font-semibold text-success" : "text-muted-foreground"
              }`}
            >
              {label}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
