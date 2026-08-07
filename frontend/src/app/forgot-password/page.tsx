"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const request = useMutation({
    mutationFn: (address: string) =>
      api.post<{ message: string }>("/api/auth/forgot-password", { email: address }),
  });

  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <Card>
        <CardHeader>
          <CardTitle>Reset your password</CardTitle>
          <CardDescription>
            Enter the email you signed up with and we&apos;ll send you a link to choose a new password.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {request.isSuccess ? (
            <div className="space-y-4">
              <p className="text-sm">{request.data?.message}</p>
              <Link href="/login" className="text-sm text-primary underline">
                Back to sign in
              </Link>
            </div>
          ) : (
            <form
              className="space-y-4"
              onSubmit={(e) => {
                e.preventDefault();
                request.mutate(email);
              }}
            >
              <div className="space-y-1.5">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
              {request.isError && (
                <p className="text-sm text-destructive">
                  {(request.error as ApiError)?.message ?? "Something went wrong. Please try again."}
                </p>
              )}
              <Button type="submit" disabled={request.isPending || !email} className="w-full">
                {request.isPending ? "Sending…" : "Send reset link"}
              </Button>
              <p className="text-center text-sm text-muted-foreground">
                Remembered it?{" "}
                <Link href="/login" className="text-primary underline">
                  Sign in
                </Link>
              </p>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
