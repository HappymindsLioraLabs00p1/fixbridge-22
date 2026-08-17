"use client";

import { useRouter } from "next/navigation";
import Link from "next/link";
import { useState } from "react";
import { useHydrated } from "@/lib/use-hydrated";
import { useLogin } from "@/lib/hooks";
import { ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";
import { brand } from "@/config/brand";
import type { UserRole } from "@/lib/types";

/** Where a person lands after signing in, by the most privileged role they hold. */
function homeFor(roles: UserRole[]) {
  if (roles.includes("admin")) return "/admin";
  if (roles.includes("contractor")) return "/contractor";
  return "/customer";
}

/**
 * Sign in.
 *
 * <p>One form, the existing endpoint, the existing token store — no second authentication path.
 * Everyone signs in here and is routed by role afterwards, because a person who has to work out
 * which of three doors is theirs before typing a password has already been failed.
 */
export default function LoginPage() {
  const router = useRouter();
  const login = useLogin();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  // Before hydration a submit button performs a native GET and silently discards the form.
  // See use-hydrated.ts.
  const hydrated = useHydrated();

  function submit(e: React.FormEvent) {
    e.preventDefault();
    login.mutate({ email, password }, { onSuccess: (res) => router.push(homeFor(res.user.roles)) });
  }

  return (
    <div className="mx-auto flex w-full max-w-md flex-col justify-center px-4 py-10 sm:py-16">
      <div className="mb-6 text-center">
        <span className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-navy text-navy-foreground" aria-hidden>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
               strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
            <path d="M3 11 12 3l9 8M6 11v9h12v-9" />
          </svg>
        </span>
        <h1 className="mt-4 text-2xl font-semibold">Welcome back</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Sign in to {brand.name} to track your repairs.
        </p>
      </div>

      <Card>
        <CardContent className="pt-6">
          <form onSubmit={submit} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                // Tells a password manager what to fill, and a phone keyboard what to show —
                // without these, mobile sign-in means typing an email on an alphabetic keypad
                // with autocapitalise fighting you.
                autoComplete="email"
                inputMode="email"
                autoCapitalize="none"
                spellCheck={false}
                autoFocus
                aria-invalid={login.isError || undefined}
                aria-describedby={login.isError ? "signin-error" : undefined}
              />
            </div>

            <div className="space-y-1.5">
              <div className="flex items-baseline justify-between">
                <Label htmlFor="password">Password</Label>
                <Link href="/forgot-password" className="text-xs font-medium text-primary hover:underline">
                  Forgot password?
                </Link>
              </div>
              <div className="relative">
                <Input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  className="pr-16"
                  aria-invalid={login.isError || undefined}
                  aria-describedby={login.isError ? "signin-error" : undefined}
                />
                {/* Typing a password blind on a phone is the most common reason a correct one gets
                    typed wrong. The control sits inside the field and stays a real button so it is
                    reachable by keyboard. */}
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-pressed={showPassword}
                  className="absolute inset-y-0 right-0 px-3 text-xs font-medium text-muted-foreground hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
            </div>

            {login.isError && (
              // Announced rather than merely displayed: a screen-reader user submitting a form does
              // not know it failed unless something tells them.
              <p id="signin-error" role="alert" className="text-sm text-destructive">
                {(login.error as ApiError)?.message ?? "Sign in failed. Check your details and try again."}
              </p>
            )}

            <Button type="submit" className="h-11 w-full" disabled={!hydrated || login.isPending}>
              {login.isPending ? "Signing in…" : "Sign in"}
            </Button>
          </form>

          <p className="mt-5 text-center text-sm text-muted-foreground">
            No account?{" "}
            <Link href="/register" className="font-medium text-primary hover:underline">
              Create one
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
