"use client";

import { useRouter } from "next/navigation";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useHydrated } from "@/lib/use-hydrated";
import { useAuth } from "@/store/auth";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { OtpInput } from "@/components/otp-input";
import { MascotSvg } from "@/components/mascot-assistant";
import { brand } from "@/config/brand";
import type { TokenResponse, UserRole } from "@/lib/types";

/** Where a person lands after signing in, by the most privileged role they hold. */
function homeFor(roles: UserRole[]) {
  if (roles.includes("admin")) return "/admin";
  if (roles.includes("contractor")) return "/contractor";
  return "/customer";
}

type Step = "welcome" | "phone-code" | "email" | "email-code" | "onboard" | "done";

/** What the verify endpoint hands back: tokens for a known account, a ticket for a new one. */
interface OtpVerifyResponse {
  tokens: TokenResponse | null;
  signupTicket: string | null;
  newUser: boolean;
}

/**
 * One door for everyone: type where to reach you, prove it with a code, and the system works out
 * whether you're signing in or joining. Nobody is asked "do you have an account?" — the database
 * knows, and asking makes the person do the computer's job.
 *
 * <p>Phone is first because it's the lowest-effort proof for the customer this product serves;
 * email is the fallback and — via "use password instead" — the door for every account that predates
 * codes, including admin. Google and Apple are announced but honest: no OAuth backend exists yet,
 * so the buttons say so instead of dying silently.
 */
export default function LoginPage() {
  const router = useRouter();
  const hydrated = useHydrated();
  const setAuth = useAuth((s) => s.setAuth);

  const [step, setStep] = useState<Step>("welcome");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [usePassword, setUsePassword] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [ticket, setTicket] = useState<string | null>(null);
  const [ticketChannel, setTicketChannel] = useState<"sms" | "email">("sms");
  const [fullName, setFullName] = useState("");
  const [onboardEmail, setOnboardEmail] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [resendIn, setResendIn] = useState(0);
  const phoneRef = useRef<HTMLInputElement>(null);

  // The resend countdown: a fixed short wait beats a customer hammering resend into the rate limit.
  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setTimeout(() => setResendIn((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [resendIn]);

  /** The active channel — which destination the current code screen is about. */
  const channel: "sms" | "email" = step === "email-code" ? "email" : "sms";
  const destination = channel === "sms" ? phone : email;

  function friendly(e: unknown): string {
    // ApiError messages are written for customers server-side; anything else gets the generic line
    // rather than a stack trace or status code.
    return e instanceof ApiError && e.message ? e.message : "Something went wrong. Please try again.";
  }

  async function sendCode(to: "sms" | "email") {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/auth/otp/send", {
        channel: to,
        destination: to === "sms" ? phone : email,
      });
      setCode("");
      setResendIn(30);
      setStep(to === "sms" ? "phone-code" : "email-code");
    } catch (e) {
      setError(friendly(e));
    } finally {
      setBusy(false);
    }
  }

  function finish(tokens: TokenResponse) {
    setAuth(tokens);
    setStep("done");
    // Long enough to read "you're in", short enough not to feel like a loading screen.
    setTimeout(() => router.push(homeFor(tokens.user.roles)), 900);
  }

  async function verifyCode(entered: string) {
    setBusy(true);
    setError(null);
    try {
      const res = await api.post<OtpVerifyResponse>("/api/auth/otp/verify", {
        channel,
        destination,
        code: entered,
      });
      if (res.tokens) {
        finish(res.tokens);
      } else if (res.signupTicket) {
        setTicket(res.signupTicket);
        setTicketChannel(channel);
        setStep("onboard");
      }
    } catch (e) {
      setError(friendly(e));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  async function completeSignup(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const tokens = await api.post<TokenResponse>("/api/auth/otp/complete", {
        signupTicket: ticket,
        fullName,
        email: ticketChannel === "sms" ? onboardEmail : undefined,
      });
      finish(tokens);
    } catch (e) {
      setError(friendly(e));
    } finally {
      setBusy(false);
    }
  }

  async function passwordLogin(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const tokens = await api.post<TokenResponse>("/api/auth/login", { email, password });
      finish(tokens);
    } catch (e) {
      setError(friendly(e));
    } finally {
      setBusy(false);
    }
  }

  /** Shared chrome under every step: reassurance and the terms, quiet but present. */
  const footer = (
    <div className="mt-6 space-y-3 text-center">
      <p className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5" aria-hidden>
          <rect x="5" y="11" width="14" height="9" rx="2" />
          <path d="M8 11V8a4 4 0 1 1 8 0v3" />
        </svg>
        Your information is protected
      </p>
      <p className="text-xs text-muted-foreground">
        By continuing, you agree to our{" "}
        <Link href="/terms" className="underline hover:text-foreground">Terms of Service</Link> and{" "}
        <Link href="/privacy" className="underline hover:text-foreground">Privacy Policy</Link>.
      </p>
    </div>
  );

  const errorLine = error && (
    <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {error}
    </p>
  );

  return (
    <div className="min-h-[calc(100vh-3.5rem)] lg:grid lg:grid-cols-2">
      {/* ---- Left: the brand panel. Desktop only — on a phone it would push the form below the fold. ---- */}
      <aside className="hidden flex-col justify-center bg-navy px-12 py-16 text-navy-foreground lg:flex xl:px-20">
        <p className="font-display text-4xl font-semibold leading-tight tracking-tight xl:text-5xl">
          Need something fixed?
          <span className="mt-1 block text-primary">We&apos;ve got you covered.</span>
        </p>
        <p className="mt-4 max-w-md text-sm text-navy-foreground/70">
          Connect with trusted professionals for repairs, maintenance, and services across NYC &amp; Long Island.
        </p>

        <div className="mt-10 flex items-end gap-6">
          <div className="shrink-0 rounded-full bg-navy-soft p-4">
            <MascotSvg pose="cheer" />
          </div>
          <ul className="space-y-3 text-sm">
            {[
              ["🔧", "Repairs", "Fix it right the first time."],
              ["🏠", "Maintenance", "Keep your home running smoothly."],
              ["⚡", "Emergency help", "We're here when you need us most."],
            ].map(([icon, title, sub]) => (
              <li key={title} className="flex items-start gap-3">
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-navy-soft text-base" aria-hidden>
                  {icon}
                </span>
                <span>
                  <span className="block font-medium">{title}</span>
                  <span className="text-navy-foreground/60">{sub}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <p className="mt-10 max-w-md rounded-xl bg-navy-soft px-4 py-3 text-xs text-navy-foreground/70">
          <span className="font-medium text-navy-foreground">Trusted. Verified. Reliable.</span>{" "}
          Real bids from licensed, background-checked pros — no subscription, no cold calls.
        </p>
      </aside>

      {/* ---- Right: the card ---- */}
      <main className="flex items-start justify-center px-4 py-8 sm:items-center sm:py-12">
        <div className="w-full max-w-md rounded-2xl border bg-card p-6 shadow-sm sm:p-8">
          {step === "welcome" && (
            <>
              <div className="text-center">
                <div className="mx-auto w-fit lg:hidden" aria-hidden>
                  <MascotSvg pose="corner" />
                </div>
                <h1 className="mt-2 font-display text-2xl font-semibold">
                  Welcome to {brand.name} <span aria-hidden>👋</span>
                </h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  Get trusted help for your home, business, or property.
                </p>
              </div>

              <form
                className="mt-6 space-y-3"
                onSubmit={(e) => {
                  e.preventDefault();
                  sendCode("sms");
                }}
                noValidate
              >
                <Label htmlFor="phone" className="sr-only">Phone number</Label>
                <div className="flex gap-2">
                  {/* US-only for now — the backend normalises to +1, matching the service area.
                      A picker offering countries that cannot sign in would be a lie. */}
                  <span className="flex h-12 shrink-0 items-center gap-1.5 rounded-xl border bg-background px-3 text-sm" aria-hidden>
                    🇺🇸 +1
                  </span>
                  <Input
                    id="phone"
                    ref={phoneRef}
                    type="tel"
                    inputMode="tel"
                    autoComplete="tel-national"
                    placeholder="Phone number"
                    className="h-12 rounded-xl text-base"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    aria-invalid={!!error || undefined}
                  />
                </div>
                {errorLine}
                <Button type="submit" className="h-12 w-full rounded-xl text-base" disabled={!hydrated || busy || !phone.trim()}>
                  {busy ? "Sending code…" : "Continue with Phone →"}
                </Button>
              </form>

              <div className="my-5 flex items-center gap-3 text-xs text-muted-foreground" aria-hidden>
                <span className="h-px flex-1 bg-border" />OR<span className="h-px flex-1 bg-border" />
              </div>

              <div className="space-y-2.5">
                {/* Honest buttons: Google/Apple have no OAuth backend yet. They answer, they just
                    don't pretend. */}
                <Button
                  type="button"
                  variant="outline"
                  className="h-12 w-full justify-center gap-3 rounded-xl text-base"
                  onClick={() => setNotice("Google sign-in is coming soon — continue with phone or email for now.")}
                >
                  <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden>
                    <path fill="#4285F4" d="M23.5 12.3c0-.9-.1-1.5-.3-2.2H12v4.1h6.5c-.1 1.1-.8 2.7-2.4 3.8l3.7 2.9c2.2-2 3.7-5 3.7-8.6z" />
                    <path fill="#34A853" d="M12 24c3.2 0 5.9-1.1 7.9-2.9l-3.7-2.9c-1 .7-2.4 1.2-4.2 1.2-3.1 0-5.8-2.1-6.8-5l-3.9 3C3.3 21.2 7.3 24 12 24z" />
                    <path fill="#FBBC05" d="M5.2 14.4c-.3-.7-.4-1.6-.4-2.4s.2-1.7.4-2.4l-3.9-3C.5 8.2 0 10 0 12s.5 3.8 1.3 5.4l3.9-3z" />
                    <path fill="#EA4335" d="M12 4.7c2.2 0 3.7 1 4.6 1.8l3.3-3.3C17.9 1.2 15.2 0 12 0 7.3 0 3.3 2.8 1.3 6.6l3.9 3c1-2.9 3.7-4.9 6.8-4.9z" />
                  </svg>
                  Continue with Google
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="h-12 w-full justify-center gap-3 rounded-xl text-base"
                  onClick={() => setNotice("Apple sign-in is coming soon — continue with phone or email for now.")}
                >
                  <svg viewBox="0 0 24 24" className="h-5 w-5 fill-foreground" aria-hidden>
                    <path d="M17.05 12.54c0-3 2.46-4.44 2.57-4.5-1.4-2.06-3.58-2.34-4.36-2.37-1.85-.19-3.6 1.09-4.54 1.09-.93 0-2.38-1.06-3.9-1.03-2 .03-3.86 1.16-4.9 2.96-2.08 3.62-.53 8.98 1.5 11.92 1 1.44 2.18 3.05 3.74 3 1.5-.06 2.07-.97 3.9-.97 1.8 0 2.33.97 3.92.94 1.62-.03 2.65-1.47 3.64-2.91 1.14-1.67 1.61-3.29 1.64-3.37-.04-.02-3.14-1.2-3.17-4.76zM14.05 3.73c.83-1 1.38-2.38 1.23-3.73-1.19.05-2.63.8-3.48 1.79-.77.88-1.44 2.29-1.26 3.63 1.33.1 2.68-.67 3.51-1.69z" />
                  </svg>
                  Continue with Apple
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="h-12 w-full justify-center gap-3 rounded-xl text-base"
                  onClick={() => {
                    setNotice(null);
                    setError(null);
                    setStep("email");
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5" aria-hidden>
                    <rect x="3" y="5" width="18" height="14" rx="2" />
                    <path d="m3 7 9 6 9-6" />
                  </svg>
                  Continue with Email
                </Button>
              </div>

              {notice && (
                <p role="status" className="mt-3 rounded-lg bg-primary-subtle px-3 py-2 text-center text-xs text-foreground">
                  {notice}
                </p>
              )}

              <p className="mt-5 text-center text-sm text-muted-foreground">
                New to {brand.name}?{" "}
                <button
                  type="button"
                  className="font-medium text-primary hover:underline"
                  onClick={() => phoneRef.current?.focus()}
                >
                  Create an account
                </button>
              </p>
              <p className="mt-2 text-center text-xs text-muted-foreground">
                Are you a professional?{" "}
                <Link href="/register" className="font-medium text-primary hover:underline">
                  Join as a pro
                </Link>
              </p>
              {footer}
            </>
          )}

          {(step === "phone-code" || step === "email-code") && (
            <>
              <button
                type="button"
                onClick={() => {
                  setError(null);
                  setCode("");
                  setStep(step === "phone-code" ? "welcome" : "email");
                }}
                className="text-sm text-muted-foreground hover:text-foreground"
                aria-label="Go back"
              >
                ← Back
              </button>
              <div className="mt-4 text-center">
                <h1 className="font-display text-2xl font-semibold">
                  {channel === "sms" ? "Verify your phone" : "Check your email"}
                </h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  We sent a 6-digit code to <span className="font-medium text-foreground">{destination}</span>
                </p>
              </div>

              <div className="mt-6 space-y-4">
                <OtpInput
                  value={code}
                  onChange={(c) => {
                    setError(null);
                    setCode(c);
                  }}
                  onComplete={verifyCode}
                  disabled={busy}
                  error={!!error}
                />
                {errorLine}
                {busy && <p className="text-center text-sm text-muted-foreground" role="status">Checking…</p>}

                <p className="text-center text-sm text-muted-foreground">
                  Didn&apos;t receive the code?{" "}
                  {resendIn > 0 ? (
                    <span className="tabular">Resend in {resendIn}s</span>
                  ) : (
                    <button
                      type="button"
                      className="font-medium text-primary hover:underline"
                      onClick={() => sendCode(channel)}
                      disabled={busy}
                    >
                      Resend code
                    </button>
                  )}
                </p>
                <p className="text-center">
                  <button
                    type="button"
                    className="text-sm text-muted-foreground underline hover:text-foreground"
                    onClick={() => {
                      setError(null);
                      setCode("");
                      setStep(step === "phone-code" ? "welcome" : "email");
                    }}
                  >
                    {channel === "sms" ? "Change phone number" : "Change email address"}
                  </button>
                </p>
              </div>
              {footer}
            </>
          )}

          {step === "email" && (
            <>
              <button
                type="button"
                onClick={() => {
                  setError(null);
                  setStep("welcome");
                }}
                className="text-sm text-muted-foreground hover:text-foreground"
                aria-label="Go back"
              >
                ← Back
              </button>
              <div className="mt-4 text-center">
                <h1 className="font-display text-2xl font-semibold">Continue with Email</h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  {usePassword
                    ? "Sign in with your email and password."
                    : "Enter your email address and we'll send you a verification code."}
                </p>
              </div>

              <form className="mt-6 space-y-3" onSubmit={usePassword ? passwordLogin : (e) => { e.preventDefault(); sendCode("email"); }} noValidate>
                <Label htmlFor="email" className="sr-only">Email address</Label>
                <Input
                  id="email"
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  autoCapitalize="none"
                  spellCheck={false}
                  placeholder="Email address"
                  className="h-12 rounded-xl text-base"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoFocus
                />
                {usePassword && (
                  <div className="relative">
                    <Label htmlFor="password" className="sr-only">Password</Label>
                    <Input
                      id="password"
                      type={showPassword ? "text" : "password"}
                      autoComplete="current-password"
                      placeholder="Password"
                      className="h-12 rounded-xl pr-16 text-base"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((v) => !v)}
                      aria-pressed={showPassword}
                      className="absolute inset-y-0 right-0 px-3 text-xs font-medium text-muted-foreground hover:text-foreground"
                    >
                      {showPassword ? "Hide" : "Show"}
                    </button>
                  </div>
                )}
                {errorLine}
                <Button
                  type="submit"
                  className="h-12 w-full rounded-xl text-base"
                  disabled={!hydrated || busy || !email.trim() || (usePassword && !password)}
                >
                  {busy ? "One moment…" : "Continue →"}
                </Button>
              </form>

              <div className="mt-4 space-y-2 text-center text-sm">
                <button
                  type="button"
                  className="font-medium text-primary hover:underline"
                  onClick={() => {
                    setError(null);
                    setUsePassword((v) => !v);
                  }}
                >
                  {usePassword ? "Email me a code instead" : "Use password instead"}
                </button>
                {usePassword && (
                  <p>
                    <Link href="/forgot-password" className="text-xs text-muted-foreground underline hover:text-foreground">
                      Forgot password?
                    </Link>
                  </p>
                )}
              </div>
              {footer}
            </>
          )}

          {step === "onboard" && (
            <>
              <div className="text-center">
                <div className="mx-auto w-fit" aria-hidden>
                  <MascotSvg pose="cheer" />
                </div>
                <h1 className="mt-2 font-display text-2xl font-semibold">
                  Welcome to {brand.name} <span aria-hidden>👋</span>
                </h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  Let&apos;s get a few details so we can help you faster.
                </p>
              </div>

              <form className="mt-6 space-y-3" onSubmit={completeSignup} noValidate>
                <div className="space-y-1.5">
                  <Label htmlFor="fullName">Your name</Label>
                  <Input
                    id="fullName"
                    autoComplete="name"
                    placeholder="First and last name"
                    className="h-12 rounded-xl text-base"
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    autoFocus
                  />
                </div>
                {ticketChannel === "sms" && (
                  <div className="space-y-1.5">
                    <Label htmlFor="onboardEmail">Email address</Label>
                    <Input
                      id="onboardEmail"
                      type="email"
                      inputMode="email"
                      autoComplete="email"
                      autoCapitalize="none"
                      spellCheck={false}
                      placeholder="you@example.com"
                      className="h-12 rounded-xl text-base"
                      value={onboardEmail}
                      onChange={(e) => setOnboardEmail(e.target.value)}
                    />
                    <p className="text-xs text-muted-foreground">
                      For receipts and updates about your repairs.
                    </p>
                  </div>
                )}
                {errorLine}
                <Button
                  type="submit"
                  className="h-12 w-full rounded-xl text-base"
                  disabled={!hydrated || busy || !fullName.trim() || (ticketChannel === "sms" && !onboardEmail.trim())}
                >
                  {busy ? "Setting up…" : "Continue →"}
                </Button>
              </form>
              {footer}
            </>
          )}

          {step === "done" && (
            <div className="py-10 text-center" role="status">
              <div className="mx-auto w-fit" aria-hidden>
                <MascotSvg pose="cheer" />
              </div>
              <h1 className="mt-4 font-display text-2xl font-semibold">
                You&apos;re all set! <span aria-hidden>🎉</span>
              </h1>
              <p className="mt-1 text-sm text-muted-foreground">Taking you to your dashboard…</p>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
