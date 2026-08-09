import type { Metadata, Viewport } from "next";
import type { ReactNode, CSSProperties } from "react";
import { Barlow_Condensed } from "next/font/google";
import "./globals.css";
import { brand } from "@/config/brand";
import { Providers } from "@/components/providers";
import { SiteHeader } from "@/components/site-header";
import { ServiceWorkerRegistration } from "@/components/service-worker";

// Heavy condensed display type, matching the FixBridge brand site.
const display = Barlow_Condensed({
  subsets: ["latin"],
  weight: ["600", "700", "900"],
  variable: "--font-display",
  display: "swap",
});

export const metadata: Metadata = {
  title: `${brand.name} — ${brand.tagline}`,
  description: brand.tagline,
  manifest: "/manifest.webmanifest",
  applicationName: brand.name,
  appleWebApp: {
    // Lets iOS run it from the home screen without Safari's chrome.
    capable: true,
    title: brand.name,
    statusBarStyle: "black-translucent",
  },
  icons: {
    icon: "/icon.svg",
    apple: "/apple-icon.png",
  },
  formatDetection: {
    // Stops iOS turning job references and figures into phone-number links.
    telephone: false,
  },
};

export const viewport: Viewport = {
  themeColor: "#0B2447",
  width: "device-width",
  initialScale: 1,
  // Room for the notch and the home indicator when running full-screen.
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  // Inject the brand primary color as a CSS variable — the whole palette derives from brand config.
  const brandStyle = { ["--brand-primary"]: brand.primaryColor } as CSSProperties;
  return (
    <html lang="en" className={`h-full ${display.variable}`}>
      <body className="flex min-h-full flex-col" style={brandStyle}>
        <Providers>
          <SiteHeader />
          <main className="w-full flex-1">{children}</main>
          <ServiceWorkerRegistration />
        </Providers>
      </body>
    </html>
  );
}
