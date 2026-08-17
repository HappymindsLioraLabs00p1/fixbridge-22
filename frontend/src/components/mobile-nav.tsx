"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "@/store/auth";
import type { UserRole } from "@/lib/types";

/**
 * Thumb-reach navigation, phone only.
 *
 * <p>The product is used standing in a kitchen with a leak, one-handed — the top of a phone is the
 * hardest place to reach and that is exactly where a header lives. Below `sm` this replaces the
 * header's links; above it the header is fine and this is hidden.
 *
 * <p>Destinations are per role and every one of them is a route that exists. The reference shows
 * Messages and Wallet, which this product has no equivalent of yet: inventing tabs that dead-end is
 * worse than a shorter bar, so they are left out until there is something behind them.
 */
type Tab = { href: string; label: string; icon: string };

const CUSTOMER: Tab[] = [
  { href: "/customer", label: "Home", icon: "M3 11 12 3l9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1v-9Z" },
  { href: "/customer/report", label: "Report", icon: "M12 5v14M5 12h14" },
  { href: "/customer/assistant", label: "Assistant", icon: "M21 12a9 9 0 1 1-3.2-6.9L21 4v6h-6" },
  { href: "/notifications", label: "Activity", icon: "M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 0 1-3.4 0" },
  { href: "/account", label: "Account", icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" },
];

const CONTRACTOR: Tab[] = [
  { href: "/contractor", label: "Jobs", icon: "M3 7h18v13H3zM8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" },
  { href: "/notifications", label: "Activity", icon: "M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 0 1-3.4 0" },
  { href: "/account", label: "Account", icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" },
];

const ADMIN: Tab[] = [
  { href: "/admin", label: "Queue", icon: "M3 7h18v13H3zM8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" },
  { href: "/admin/compliance", label: "Compliance", icon: "M12 3l7 3v6c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6l7-3Z" },
  { href: "/admin/reports", label: "Reports", icon: "M4 20V10M10 20V4M16 20v-7M22 20H2" },
  { href: "/account", label: "Account", icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" },
];

function tabsFor(roles: UserRole[]): Tab[] {
  if (roles.includes("admin")) return ADMIN;
  if (roles.includes("contractor")) return CONTRACTOR;
  return CUSTOMER;
}

export function MobileNav() {
  const pathname = usePathname();
  const { user } = useAuth();
  // Persisted auth is only safe to read after mount, or the server and client markup disagree.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  if (!mounted || !user) return null;
  const tabs = tabsFor(user.roles);

  return (
    <nav
      aria-label="Primary"
      className="fixed inset-x-0 bottom-0 z-40 border-t bg-card pb-[env(safe-area-inset-bottom)] sm:hidden"
    >
      <ul className="mx-auto flex max-w-lg">
        {tabs.map((tab) => {
          // Exact match for the dashboard root, prefix otherwise — so /customer/report does not
          // light up Home as well as Report.
          const active =
            tab.href === "/customer" || tab.href === "/contractor" || tab.href === "/admin"
              ? pathname === tab.href
              : pathname.startsWith(tab.href);
          return (
            <li key={tab.href} className="flex-1">
              <Link
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={`flex h-14 flex-col items-center justify-center gap-0.5 text-[10px] font-medium transition-colors ${
                  active ? "text-primary" : "text-muted-foreground"
                }`}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
                     strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden>
                  <path d={tab.icon} />
                </svg>
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
