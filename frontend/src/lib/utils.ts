import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Merge Tailwind class names, resolving conflicts (ShadCN convention). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format integer cents as USD. */
export function formatCents(cents: number | null | undefined): string {
  if (cents == null) return "—";
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(cents / 100);
}

/** Format a retail range, or the "assessment required" message. */
export function formatRange(low?: number | null, high?: number | null): string {
  if (low == null || high == null) return "On-site assessment required";
  return `${formatCents(low)} – ${formatCents(high)}`;
}
