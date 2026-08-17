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
    <header className="border-b border-white/10 bg-navy text-navy-foreground pt-[env(safe-area-inset-top)]">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5">
        <Link href={iconHref} aria-label={`${brand.name} — go to dashboard`} className="flex items-center gap-2">
          {/* Wordmark rather than a boxed icon: on a navy header the box was a navy square on a
              navy ground, so the logo read as a hole. */}
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-primary text-white" aria-hidden>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                 strokeLinecap="round" strokeLinejoin="round" className="h-4.5 w-4.5">
              <path d="M3 11 12 3l9 8M6 11v9h12v-9" />
            </svg>
          </span>
          <span className="font-display text-lg tracking-tight">
            Fix<span className="text-primary">Bridge</span>
          </span>
        </Link>

        <nav className="flex items-center gap-2">
          {mounted && user ? (
            <>
              <Link href={HOME_FOR_ROLE[role!] ?? "/customer"} className="hidden sm:inline">
                <Button variant="ghost" size="sm" className="text-white/80 hover:bg-white/10 hover:text-white">
                  Dashboard
                </Button>
              </Link>
              <Link href="/plans" className="hidden sm:inline">
                <Button variant="ghost" size="sm" className="text-white/80 hover:bg-white/10 hover:text-white">
                  Plans
                </Button>
              </Link>
              <Link href="/notifications" className="hidden sm:inline">
                <Button variant="ghost" size="sm" className="text-white/80 hover:bg-white/10 hover:text-white">
                  Activity
                </Button>
              </Link>
              {/* The email doubles as the way into account settings. */}
              <Link href="/account" className="hidden sm:inline">
                <span className="text-sm text-white/70 underline-offset-4 hover:underline">
                  {user.email}
                </span>
              </Link>
              <Button
                variant="outline"
                size="sm"
                className="border-white/25 bg-transparent text-white hover:bg-white/10"
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
              <Link href="/services" className="hidden sm:inline">

                <Button variant="ghost" size="sm" className="text-white/80 hover:bg-white/10 hover:text-white">Services</Button>

              </Link>
              <Link href="/login">
                <Button variant="ghost" size="sm" className="text-white/80 hover:bg-white/10 hover:text-white">
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
