/**
 * Single source of truth for brand identity. "FixBridge" is a working name — the name, logo, colors,
 * domain and support email are NEVER hard-coded elsewhere. Rebrand the whole app by editing this file
 * (values come from env where provided so staging/production can differ).
 */
export const brand = {
  name: process.env.NEXT_PUBLIC_BRAND_NAME ?? "FixBridge",
  tagline: "AI-guided property care, verified pros, one clear price.",
  supportEmail: process.env.NEXT_PUBLIC_BRAND_SUPPORT_EMAIL ?? "support@example.com",
  domain: process.env.NEXT_PUBLIC_BRAND_DOMAIN ?? "example.com",
  primaryColor: process.env.NEXT_PUBLIC_BRAND_PRIMARY_COLOR ?? "#1f6feb",
  // A short wordmark used where a logo image is not yet configured.
  wordmark: process.env.NEXT_PUBLIC_BRAND_NAME ?? "FixBridge",
} as const;

export type Brand = typeof brand;
