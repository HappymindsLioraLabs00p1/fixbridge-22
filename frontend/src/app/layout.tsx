import type { Metadata } from "next";
import type { ReactNode, CSSProperties } from "react";
import "./globals.css";
import { brand } from "@/config/brand";
import { Providers } from "@/components/providers";
import { SiteHeader } from "@/components/site-header";

export const metadata: Metadata = {
  title: `${brand.name} — ${brand.tagline}`,
  description: brand.tagline,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  // Inject the brand primary color as a CSS variable — the whole palette derives from brand config.
  const brandStyle = { ["--brand-primary"]: brand.primaryColor } as CSSProperties;
  return (
    <html lang="en" className="h-full">
      <body className="min-h-full flex flex-col" style={brandStyle}>
        <Providers>
          <SiteHeader />
          <main className="flex-1 w-full">{children}</main>
        </Providers>
      </body>
    </html>
  );
}
