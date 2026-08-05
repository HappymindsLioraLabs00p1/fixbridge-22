"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "@/store/auth";
import type { UserRole } from "@/lib/types";

/**
 * Client-side guard for role areas. Real authorization is enforced by the backend on every request;
 * this only improves UX by redirecting unauthenticated/again-wrong-role users.
 */
export function RequireRole({ role, children }: { role: UserRole; children: ReactNode }) {
  const router = useRouter();
  const { user } = useAuth();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (mounted && !user) router.replace("/login");
  }, [mounted, user, router]);

  if (!mounted) return null;
  if (!user) return null;
  if (!user.roles.includes(role)) {
    return (
      <div className="mx-auto max-w-md px-4 py-16 text-center text-muted-foreground">
        Your account does not have access to this area.
      </div>
    );
  }
  return <>{children}</>;
}
