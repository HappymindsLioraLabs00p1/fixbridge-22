"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { brand } from "@/config/brand";
import { useAuth } from "@/store/auth";
import { Button } from "@/components/ui/button";
import type { UserRole } from "@/lib/types";

const HOME_FOR_ROLE: Record<string, string> = {
  admin: "/admin",
  contractor: "/contractor",
  customer: "/customer",
};

function primaryRole(roles: UserRole[]): string {
  if (roles.includes("admin")) return "admin";
  if (roles.includes("contractor")) return "contractor";
  return "customer";
}

export function SiteHeader() {
  const router = useRouter();
  const { user, clear } = useAuth();
  // Avoid hydration mismatch: only read persisted auth after mount.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const role = mounted && user ? primaryRole(user.roles) : null;
  // The app icon routes into the app: the signed-in user's dashboard, otherwise the landing page.
  const iconHref = role ? HOME_FOR_ROLE[role] ?? "/customer" : "/";

  return (
    // Installed to the home screen, iOS draws the page *under* the status bar — the manifest asks
    // for a translucent bar and a cover viewport, which is what gives the app its edge-to-edge
    // look. Without this inset the clock lands on the logo and the battery icon on the nav.
    // In a browser tab the inset resolves to zero, so this costs nothing there.
    <header className="border-b bg-background pt-[env(safe-area-inset-top)]">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5">
        <Link href={iconHref} aria-label={`${brand.name} — go to dashboard`} className="flex items-center">
          {/* App icon: FIX over BRIDGE·AI, stacked on dark blue. */}
          <span className="flex flex-col items-center justify-center rounded-lg bg-[#0B2447] px-2.5 py-1 leading-[0.82] text-white shadow-sm">
            <span className="font-display text-xl tracking-wide">FIX</span>
            <span className="font-display text-[10px] tracking-[0.12em]">
              BRIDGE <span className="text-primary">AI</span>
            </span>
          </span>
        </Link>

        <nav className="flex items-center gap-2">
          {mounted && user ? (
            <>
              <Link href={HOME_FOR_ROLE[role!] ?? "/customer"}>
                <Button variant="ghost" size="sm">
                  Dashboard
                </Button>
              </Link>
              <Link href="/plans">
                <Button variant="ghost" size="sm">
                  Plans
                </Button>
              </Link>
              <Link href="/notifications">
                <Button variant="ghost" size="sm">
                  Activity
                </Button>
              </Link>
              {/* The email doubles as the way into account settings. */}
              <Link href="/account" className="hidden sm:inline">
                <span className="text-sm text-muted-foreground underline-offset-4 hover:underline">
                  {user.email}
                </span>
              </Link>
              <Link href="/account" className="sm:hidden">
                <Button variant="ghost" size="sm">
                  Account
                </Button>
              </Link>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  clear();
                  router.push("/");
                }}
              >
                Sign out
              </Button>
            </>
          ) : (
            <>
              <Link href="/login">
                <Button variant="ghost" size="sm">
                  Sign In
                </Button>
              </Link>
              <Link href="/register">
                <Button size="sm">Post a Repair</Button>
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
