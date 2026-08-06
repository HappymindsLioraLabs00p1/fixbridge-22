import type { Metadata } from "next";
import type { ReactNode, CSSProperties } from "react";
import { Barlow_Condensed } from "next/font/google";
import "./globals.css";
import { brand } from "@/config/brand";
import { Providers } from "@/components/providers";
import { SiteHeader } from "@/components/site-header";

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
        </Providers>
      </body>
    </html>
  );
}
