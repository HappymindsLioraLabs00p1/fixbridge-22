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

  return (
    <header className="border-b bg-card">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
        <Link href="/" className="flex items-center gap-2 font-semibold">
          <span
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-primary-foreground"
            style={{ background: "var(--primary)" }}
          >
            {brand.wordmark.charAt(0)}
          </span>
          <span>{brand.name}</span>
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
              <span className="hidden text-sm text-muted-foreground sm:inline">{user.email}</span>
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
                  Sign in
                </Button>
              </Link>
              <Link href="/register">
                <Button size="sm">Get started</Button>
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
