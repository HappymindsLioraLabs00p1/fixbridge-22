"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

function ResetPasswordForm() {
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");

  const reset = useMutation({
    mutationFn: () =>
      api.post<{ message: string }>("/api/auth/reset-password", { token, newPassword: password }),
  });

  const tooShort = password.length > 0 && password.length < 8;
  const mismatch = confirm.length > 0 && password !== confirm;

  if (!token) {
    return (
      <p className="text-sm">
        This link is missing its token.{" "}
        <Link href="/forgot-password" className="text-primary underline">
          Request a new one
        </Link>
        .
      </p>
    );
  }

  if (reset.isSuccess) {
    return (
      <div className="space-y-4">
        <p className="text-sm">{reset.data?.message}</p>
        <Link href="/login">
          <Button>Sign in</Button>
        </Link>
      </div>
    );
  }

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        reset.mutate();
      }}
    >
      <div className="space-y-1.5">
        <Label htmlFor="password">New password</Label>
        <Input
          id="password"
          type="password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {tooShort && <p className="text-xs text-destructive">Use at least 8 characters.</p>}
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="confirm">Confirm new password</Label>
        <Input
          id="confirm"
          type="password"
          required
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        {mismatch && <p className="text-xs text-destructive">These passwords don&apos;t match.</p>}
      </div>
      {reset.isError && (
        <p className="text-sm text-destructive">
          {(reset.error as ApiError)?.message ?? "Could not reset the password. Please try again."}
        </p>
      )}
      <Button
        type="submit"
        disabled={reset.isPending || password.length < 8 || password !== confirm}
        className="w-full"
      >
        {reset.isPending ? "Saving…" : "Set new password"}
      </Button>
    </form>
  );
}

export default function ResetPasswordPage() {
  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <Card>
        <CardHeader>
          <CardTitle>Choose a new password</CardTitle>
          <CardDescription>This link can only be used once.</CardDescription>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<p className="text-sm text-muted-foreground">Loading…</p>}>
            <ResetPasswordForm />
          </Suspense>
        </CardContent>
      </Card>
    </div>
  );
}
