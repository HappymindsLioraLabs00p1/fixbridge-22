"use client";

import { useState } from "react";
import { useReviewEligibility, useSubmitReview } from "@/lib/hooks";
import { Button } from "@/components/ui/button";
import type { ApiError } from "@/lib/api";

/**
 * The customer's rating of finished work.
 *
 * <p>This is what makes contractor ranking mean anything: matching scores partly on rating, and
 * without a way to submit one every contractor sat permanently at the unrated baseline. Eligibility
 * is decided by the server — a job the customer doesn't own, isn't finished, or has already been
 * rated returns false — so this renders nothing rather than offering an action that would be
 * refused.
 */
export function RateContractor({ jobId }: { jobId: string }) {
  const { data: eligibility } = useReviewEligibility(jobId);
  const submit = useSubmitReview();
  const [rating, setRating] = useState(0);
  const [hovered, setHovered] = useState(0);
  const [comment, setComment] = useState("");

  if (submit.isSuccess) {
    return (
      <div className="mt-4 border-t pt-4">
        <p className="text-sm font-medium text-[var(--success)]">
          Thanks — your rating helps the next customer choose. ✓
        </p>
      </div>
    );
  }

  if (!eligibility?.canReview) return null;

  const shown = hovered || rating;

  return (
    <div className="mt-4 space-y-3 border-t pt-4">
      <div>
        <p className="text-sm font-medium">How did it go?</p>
        <p className="mt-0.5 text-xs text-muted-foreground">
          Your rating is shown to other customers choosing a contractor.
        </p>
      </div>

      {/* A radio group rather than buttons: the rating is one choice out of five, and screen
          readers and keyboards get that behaviour for free. */}
      <fieldset
        className="flex gap-1"
        onMouseLeave={() => setHovered(0)}
        aria-label="Rating out of 5"
      >
        {[1, 2, 3, 4, 5].map((n) => (
          <label
            key={n}
            className="cursor-pointer text-2xl leading-none"
            onMouseEnter={() => setHovered(n)}
            style={{ color: n <= shown ? "var(--warning)" : "var(--muted-foreground)" }}
          >
            <input
              type="radio"
              name={`rating-${jobId}`}
              value={n}
              checked={rating === n}
              onChange={() => setRating(n)}
              className="sr-only"
            />
            <span aria-label={`${n} star${n > 1 ? "s" : ""}`}>{n <= shown ? "★" : "☆"}</span>
          </label>
        ))}
      </fieldset>

      <textarea
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        maxLength={2000}
        rows={3}
        placeholder="Anything worth mentioning? (optional)"
        className="w-full rounded-md border bg-transparent p-2 text-sm"
      />

      <div className="space-y-2">
        <Button
          disabled={rating === 0 || submit.isPending}
          onClick={() => submit.mutate({ jobId, rating, comment: comment.trim() || undefined })}
        >
          {submit.isPending ? "Submitting…" : "Submit rating"}
        </Button>
        {submit.isError && (
          <p className="text-sm text-destructive">
            {(submit.error as ApiError)?.message ?? "Could not submit. Please try again."}
          </p>
        )}
      </div>
    </div>
  );
}
