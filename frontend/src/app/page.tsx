import Link from "next/link";
import { brand } from "@/config/brand";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const STEPS = [
  { t: "Report an issue", d: "Describe the problem and add photos. AI organizes it in seconds." },
  { t: "See a clear estimate", d: "One transparent service range — no surprise pricing." },
  { t: "A verified pro is dispatched", d: "We coordinate a vetted contractor and schedule the work." },
  { t: "Approve, pay, done", d: "Approve the proposal, pay securely, and keep the full record." },
];

export default function Home() {
  return (
    <div className="mx-auto max-w-5xl px-4">
      <section className="py-16 text-center sm:py-24">
        <h1 className="mx-auto max-w-2xl text-4xl font-bold tracking-tight sm:text-5xl">
          {brand.tagline}
        </h1>
        <p className="mx-auto mt-4 max-w-xl text-lg text-muted-foreground">
          {brand.name} turns scattered calls, texts and quotes into one controlled workflow — from
          issue to resolution.
        </p>
        <div className="mt-8 flex justify-center gap-3">
          <Link href="/register">
            <Button size="lg">Report an issue</Button>
          </Link>
          <Link href="/login">
            <Button size="lg" variant="outline">
              Sign in
            </Button>
          </Link>
        </div>
      </section>

      <section className="grid gap-4 pb-20 sm:grid-cols-2 lg:grid-cols-4">
        {STEPS.map((s, i) => (
          <Card key={s.t}>
            <CardHeader>
              <div
                className="mb-2 inline-flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold text-primary-foreground"
                style={{ background: "var(--primary)" }}
              >
                {i + 1}
              </div>
              <CardTitle className="text-base">{s.t}</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">{s.d}</p>
            </CardContent>
          </Card>
        ))}
      </section>
    </div>
  );
}
