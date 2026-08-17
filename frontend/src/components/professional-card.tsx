import type { AssignedProfessional } from "@/lib/types";

/**
 * Who is coming to the property.
 *
 * <p>Three deliberate absences, each because the thing behind it does not exist:
 *
 * <p>No photograph — the contractor record has no image field anywhere in the domain, so initials
 * stand in rather than a stock face that implies a person we cannot show.
 *
 * <p>No Call button — the number is masked on the server and only its last four digits are ever
 * sent, so nothing here could dial it. It is shown to help someone recognise an incoming call, not
 * to place one.
 *
 * <p>No Message button — the application has no messaging of any kind. A button opening nothing is
 * worse than its absence.
 */
export function ProfessionalCard({ professional }: { professional: AssignedProfessional }) {
  const initials = professional.businessName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? "")
    .join("");

  return (
    <div className="rounded-xl border bg-card p-4">
      <p className="text-sm font-semibold">Your professional</p>

      <div className="mt-3 flex items-start gap-3">
        <span
          aria-hidden
          className="grid h-12 w-12 shrink-0 place-items-center rounded-full bg-navy text-sm font-semibold text-navy-foreground"
        >
          {initials || "?"}
        </span>

        <div className="min-w-0 flex-1">
          {/* Long trading names are common and must wrap rather than push the card sideways. */}
          <p className="font-semibold leading-snug break-words">{professional.businessName}</p>

          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
            {professional.rating != null ? (
              <span className="inline-flex items-center gap-1">
                <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4 text-primary" aria-hidden>
                  <path d="m12 17.3-6.2 3.7 1.6-7L2 9.2l7.1-.6L12 2l2.9 6.6 7.1.6-5.4 4.8 1.6 7z" />
                </svg>
                <span className="tabular">{professional.rating.toFixed(1)}</span>
                <span className="text-muted-foreground">
                  ({professional.reviewCount} review{professional.reviewCount === 1 ? "" : "s"})
                </span>
              </span>
            ) : (
              // Unrated is not badly rated, and saying "0.0" would imply the latter.
              <span className="text-muted-foreground">No reviews yet</span>
            )}

            {professional.verified && (
              <span className="inline-flex items-center gap-1 rounded-full bg-success-subtle px-2 py-0.5 text-xs font-medium text-success">
                <svg viewBox="0 0 24 24" fill="currentColor" className="h-3.5 w-3.5" aria-hidden>
                  <path d="M12 2 4 5v6c0 5 3.4 8.7 8 10 4.6-1.3 8-5 8-10V5l-8-3Zm-1 13.4-3.5-3.5 1.4-1.4 2.1 2.1 4.6-4.6 1.4 1.4-6 6Z" />
                </svg>
                Licence &amp; insurance verified
              </span>
            )}
          </div>

          {professional.maskedPhone && (
            <p className="mt-2 text-sm text-muted-foreground">
              Calls from <span className="tabular">{professional.maskedPhone}</span>
            </p>
          )}
        </div>
      </div>

      <p className="mt-3 border-t pt-3 text-xs text-muted-foreground">
        We keep your professional&apos;s number private. Need to reach them? Contact FixBridge
        support and we&apos;ll pass it on.
      </p>
    </div>
  );
}

/** Shown while the job is loading, so the card does not pop in and shift everything below it. */
export function ProfessionalCardSkeleton() {
  return (
    <div className="rounded-xl border bg-card p-4">
      <div className="h-4 w-32 animate-pulse rounded bg-muted" />
      <div className="mt-3 flex items-center gap-3">
        <div className="h-12 w-12 animate-pulse rounded-full bg-muted" />
        <div className="flex-1 space-y-2">
          <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
          <div className="h-3 w-1/3 animate-pulse rounded bg-muted" />
        </div>
      </div>
    </div>
  );
}
