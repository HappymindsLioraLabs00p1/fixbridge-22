import type { MetadataRoute } from "next";
import { brand } from "@/config/brand";

/**
 * Web app manifest — this is what lets a phone install FixBridge to the home screen and run it
 * without browser chrome. Android also requires a service worker with a fetch handler before it
 * will offer the install prompt (see public/sw.js).
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: `${brand.name} — ${brand.tagline}`,
    short_name: brand.name,
    description:
      "Report a home repair, get an AI assessment, and track the job through to completion.",
    start_url: "/",
    // Opens without the browser address bar, so it reads as an app rather than a bookmark.
    display: "standalone",
    orientation: "portrait",
    // Matches the app rather than the icon card: theme_color tints the OS chrome and is the navy of
    // the site header (the same value as metadata.themeColor in layout.tsx), while background_color
    // is the page background the launch screen hands over to.
    background_color: "#f7f9fc",
    theme_color: "#071a3d",
    categories: ["business", "productivity", "utilities"],
    icons: [
      // Vector first — anything that can use it gets a crisp icon at any size. The PNGs below are
      // generated from this same file, so the mark cannot drift between formats.
      { src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      // Composed differently from the icons above: full-bleed navy with the mark inset to the safe
      // zone, so Android's crop to a circle or squircle cannot shave the wordmark off.
      { src: "/icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      {
        name: "Report an issue",
        short_name: "Report",
        description: "Describe a problem and get an assessment",
        url: "/customer/report",
      },
    ],
  };
}
