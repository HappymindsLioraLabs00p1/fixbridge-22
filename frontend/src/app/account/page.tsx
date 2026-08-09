"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { RequireAuth } from "@/components/require-auth";
import { api, ApiError } from "@/lib/api";
import { useAuth } from "@/store/auth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function AccountPage() {
  const user = useAuth((s) => s.user);

  return (
    <RequireAuth>
      <div className="mx-auto max-w-xl space-y-6 px-4 py-8">
        <div>
          <h1 className="text-2xl font-bold">Your account</h1>
          <p className="text-sm text-muted-foreground">
            {user?.email}
            {user?.roles?.length ? ` · ${user.roles.join(", ")}` : ""}
          </p>
        </div>
        <ChangePasswordCard />
      </div>
    </RequireAuth>
  );
}

function ChangePasswordCard() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");

  const change = useMutation({
    mutationFn: () =>
      api.post<{ message: string }>("/api/auth/change-password", {
        currentPassword: current,
        newPassword: next,
      }),
    onSuccess: () => {
      setCurrent("");
      setNext("");
      setConfirm("");
    },
  });

  const tooShort = next.length > 0 && next.length < 8;
  const mismatch = confirm.length > 0 && next !== confirm;
  const ready = current.length > 0 && next.length >= 8 && next === confirm;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Change password</CardTitle>
        <CardDescription>
          Takes effect immediately. You&apos;ll stay signed in on this device.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            change.mutate();
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="current">Current password</Label>
            <Input
              id="current"
              type="password"
              autoComplete="current-password"
              required
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="next">New password</Label>
            <Input
              id="next"
              type="password"
              autoComplete="new-password"
              required
              value={next}
              onChange={(e) => setNext(e.target.value)}
            />
            {tooShort && <p className="text-xs text-destructive">Use at least 8 characters.</p>}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="confirm">Confirm new password</Label>
            <Input
              id="confirm"
              type="password"
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
            />
            {mismatch && <p className="text-xs text-destructive">These passwords don&apos;t match.</p>}
          </div>

          {change.isError && (
            <p className="text-sm text-destructive">
              {(change.error as ApiError)?.message ?? "Could not change the password."}
            </p>
          )}
          {change.isSuccess && (
            <p className="text-sm text-[var(--success)]">{change.data?.message}</p>
          )}

          <Button type="submit" disabled={!ready || change.isPending} className="w-full sm:w-auto">
            {change.isPending ? "Saving…" : "Change password"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
