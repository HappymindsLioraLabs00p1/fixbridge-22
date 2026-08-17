import type { Metadata, Viewport } from "next";
import type { ReactNode, CSSProperties } from "react";
import { Inter, Poppins } from "next/font/google";
import "./globals.css";
import { brand } from "@/config/brand";
import { Providers } from "@/components/providers";
import { SiteHeader } from "@/components/site-header";
import { MobileNav } from "@/components/mobile-nav";
import { ServiceWorkerRegistration } from "@/components/service-worker";

// Two faces doing two jobs. Inter runs the interface — it was drawn for screens at small sizes,
// which is most of this product: labels, figures, status. Poppins carries headings and the
// wordmark, where its geometry reads as approachable rather than corporate.
const body = Inter({
  subsets: ["latin"],
  variable: "--font-body",
  display: "swap",
});

const display = Poppins({
  subsets: ["latin"],
  weight: ["500", "600", "700"],
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
  // Matches --navy, so the browser and iOS chrome continue the header rather than framing it.
  themeColor: "#071A3D",
  width: "device-width",
  initialScale: 1,
  // Room for the notch and the home indicator when running full-screen.
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  // Inject the brand primary color as a CSS variable — the whole palette derives from brand config.
  const brandStyle = { ["--brand-primary"]: brand.primaryColor } as CSSProperties;
  return (
    <html lang="en" className={`h-full ${body.variable} ${display.variable}`}>
      <body className="flex min-h-full flex-col" style={brandStyle}>
        <Providers>
          <SiteHeader />
          {/* Bottom padding clears the fixed mobile bar so the last control is never under it. */}
          <main className="w-full flex-1 pb-16 sm:pb-0">{children}</main>
          <MobileNav />
          <ServiceWorkerRegistration />
        </Providers>
      </body>
    </html>
  );
}
