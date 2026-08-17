/**
 * Single source of truth for brand identity. "FixBridge" is a working name — the name, logo, colors,
 * domain and support email are NEVER hard-coded elsewhere. Rebrand the whole app by editing this file
 * (values come from env where provided so staging/production can differ).
 */
export const brand = {
  name: process.env.NEXT_PUBLIC_BRAND_NAME ?? "FixBridge",
  tagline: "Home repair, fixed by AI.",
  region: "NYC & Long Island",
  established: "2026",
  supportEmail: process.env.NEXT_PUBLIC_BRAND_SUPPORT_EMAIL ?? "support@example.com",
  domain: process.env.NEXT_PUBLIC_BRAND_DOMAIN ?? "example.com",
  // Coral accent, matching the FixBridge brand.
  primaryColor: process.env.NEXT_PUBLIC_BRAND_PRIMARY_COLOR ?? "#FF6B00",
  // Split wordmark: "FIX" (ink) + "BRIDGE" (accent) + "AI" badge.
  wordmark: process.env.NEXT_PUBLIC_BRAND_NAME ?? "FixBridge",

  /**
   * The registered entity, for places that legally require it — invoices, receipts, contracts,
   * terms and privacy policy.
   *
   * Deliberately unset during beta. The real name is not yet decided, and inventing one would put
   * a fictitious legal entity on documents people rely on. Callers must treat an empty value as
   * "not yet available" and omit the line rather than substitute the product name: FixBridge is a
   * product, and claiming it is the contracting company would be untrue.
   */
  legalCompanyName: process.env.NEXT_PUBLIC_LEGAL_COMPANY_NAME ?? "",

  /** Shown wherever a copyright line appears. Product name until the entity is registered. */
  copyrightYear: "2026",
} as const;

/**
 * The copyright line. Falls back to the product name while no legal entity is configured, so the
 * footer is never blank and never fictitious.
 */
export function copyrightLine(): string {
  const holder = brand.legalCompanyName || brand.name;
  return `© ${brand.copyrightYear} ${holder}`;
}

export type Brand = typeof brand;
