"use client";

import Link from "next/link";
import { Suspense, useEffect, useRef } from "react";
import { useSearchParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

function VerifyEmail() {
  const token = useSearchParams().get("token") ?? "";
  const started = useRef(false);

  const verify = useMutation({
    mutationFn: () => api.post<{ message: string }>("/api/auth/verify-email", { token }),
  });

  // Verify as soon as the page opens — the visitor clicked the link, that's the confirmation.
  useEffect(() => {
    if (token && !started.current) {
      started.current = true;
      verify.mutate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  if (!token) {
    return <p className="text-sm">This link is missing its token. Please use the link from your email.</p>;
  }
  if (verify.isPending) {
    return <p className="text-sm text-muted-foreground">Confirming your email…</p>;
  }
  if (verify.isError) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-destructive">
          {(verify.error as ApiError)?.message ?? "We couldn't confirm this link."}
        </p>
        <p className="text-sm text-muted-foreground">
          Verification links expire and can only be used once. Sign in and we&apos;ll send a fresh one.
        </p>
        <Link href="/login">
          <Button>Go to sign in</Button>
        </Link>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <p className="text-sm">{verify.data?.message}</p>
      <Link href="/login">
        <Button>Continue</Button>
      </Link>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <Card>
        <CardHeader>
          <CardTitle>Confirm your email</CardTitle>
          <CardDescription>Checking the link you followed from your inbox.</CardDescription>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<p className="text-sm text-muted-foreground">Loading…</p>}>
            <VerifyEmail />
          </Suspense>
        </CardContent>
      </Card>
    </div>
  );
}
