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
  primaryColor: process.env.NEXT_PUBLIC_BRAND_PRIMARY_COLOR ?? "#FF4D1C",
  // Split wordmark: "FIX" (ink) + "BRIDGE" (accent) + "AI" badge.
  wordmark: process.env.NEXT_PUBLIC_BRAND_NAME ?? "FixBridge",
} as const;

export type Brand = typeof brand;
