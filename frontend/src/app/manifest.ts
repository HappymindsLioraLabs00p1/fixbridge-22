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
    background_color: "#0B2447",
    theme_color: "#0B2447",
    categories: ["business", "productivity", "utilities"],
    icons: [
      // Maskable so Android can crop it to the device's icon shape without clipping the wordmark.
      { src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
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
