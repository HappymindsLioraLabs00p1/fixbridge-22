import Link from "next/link";
import { brand } from "@/config/brand";
import { Button } from "@/components/ui/button";
import { MascotAssistant } from "@/components/mascot-assistant";

const TICKER = [
  "Licensed & vetted contractors",
  "AI repair assessment",
  "No subscription needed",
  brand.region,
  "Free to post",
  "Real bids only",
  "Background-checked pros",
  "Transparent pricing",
];

const STEPS = [
  { n: "01", t: "Describe the problem", d: "Tell us what's broken — in plain English. Upload photos. Our AI handles the rest." },
  { n: "02", t: "Get your AI assessment", d: "Receive a detailed repair breakdown, estimated cost range, and urgency rating within minutes." },
  { n: "03", t: "Review real bids", d: "Licensed, vetted local contractors submit priced estimates. Compare, choose, and book." },
];

const ASSESS_BULLETS = [
  "Cost range estimate for your NYC / LI neighborhood",
  "Urgency rating — cosmetic to emergency",
  "Likely root cause & recommended repair approach",
  "Smart questions to ask your contractor",
];

const FEATURES = [
  { t: "AI in minutes", d: "Instant repair assessment" },
  { t: "Vetted pros only", d: "Background-checked contractors" },
  { t: "No subscription", d: "Free to post, always" },
  { t: "Fast turnaround", d: "First bid under 48 hrs avg." },
];

const STATS = [
  { v: "2,847", l: "Jobs completed" },
  { v: "4.9★", l: "Average rating" },
  { v: "500+", l: "Vetted contractors" },
  { v: "<48h", l: "Avg. first bid" },
];

const REVIEWS = [
  { q: "My ceiling was leaking the night before Thanksgiving. FixBridge had three bids by morning — and the contractor who won was incredible.", n: "Maria Santos", loc: "Astoria, Queens", saved: "$800 saved vs. initial quote" },
  { q: "I thought I needed a full HVAC replacement. The AI flagged it as a capacitor issue — $180 fix. Nobody tried to upsell me.", n: "Tony Marchetti", loc: "Huntington, Long Island", saved: "$2,400 saved vs. initial quote" },
  { q: "The AI broke down my panel upgrade better than any contractor I'd spoken to. I finally understood what I was paying for.", n: "Devon Williams", loc: "Flushing, Queens", saved: "$1,100 saved vs. initial quote" },
  { q: "I got four bids within 24 hours of posting. The AI estimate was spot on — the winning contractor came in right at the middle of the range.", n: "Rachel Kim", loc: "Park Slope, Brooklyn", saved: "$650 saved vs. initial quote" },
];

export default function Home() {
  return (
    <div>
      {/* Hero */}
      <section className="mx-auto max-w-6xl px-5 pb-16 pt-14 sm:pt-20">
        <div className="mb-6 flex items-center gap-3 font-mono text-xs uppercase tracking-widest text-primary">
          <span>{brand.region}</span>
          <span className="h-px w-10 bg-primary/50" />
          <span className="text-muted-foreground">Est. {brand.established}</span>
        </div>
        <h1 className="font-display text-6xl leading-[0.92] tracking-tight sm:text-8xl">
          <span className="block">HOME</span>
          <span className="block">REPAIR,</span>
          <span className="block" style={{ WebkitTextStroke: "2px var(--primary)", color: "transparent" }}>FIXED</span>
          <span className="block">BY AI.</span>
        </h1>
        <p className="mt-8 max-w-xl text-lg text-muted-foreground">
          Describe your problem. Get an AI assessment. Receive real bids from vetted local
          contractors — no subscription, no cold calls.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <Link href="/register"><Button size="lg">Describe Your Problem →</Button></Link>
          <Link href="#how"><Button size="lg" variant="outline">How It Works</Button></Link>
        </div>
      </section>

      {/* Marquee */}
      <div className="overflow-hidden border-y bg-foreground py-3 text-background">
        <div className="fb-marquee flex w-max gap-6 whitespace-nowrap font-mono text-xs uppercase tracking-widest">
          {[...TICKER, ...TICKER, ...TICKER, ...TICKER].map((item, i) => (
            <span key={i} className="flex items-center gap-6">
              {item}
              <span className="text-primary">◆</span>
            </span>
          ))}
        </div>
      </div>

      {/* Process */}
      <section id="how" className="mx-auto max-w-6xl px-5 py-20">
        <div className="mb-12 flex flex-wrap items-end justify-between gap-4">
          <p className="font-mono text-xs uppercase tracking-widest text-primary">Process · 3 steps</p>
          <h2 className="font-display text-4xl leading-none sm:text-5xl">NO GUESSWORK.<br />JUST RESULTS.</h2>
        </div>
        <div className="grid gap-6 md:grid-cols-3">
          {STEPS.map((s) => (
            <div key={s.n} className="rounded-lg border bg-card p-6">
              <div className="font-display text-5xl text-primary">{s.n}</div>
              <h3 className="mt-4 font-display text-2xl uppercase leading-tight">{s.t}</h3>
              <p className="mt-2 text-sm text-muted-foreground">{s.d}</p>
            </div>
          ))}
        </div>
      </section>

      {/* AI assessment engine */}
      <section className="border-t bg-secondary">
        <div className="mx-auto grid max-w-6xl gap-12 px-5 py-20 lg:grid-cols-2 lg:items-center">
          <div>
            <p className="font-mono text-xs uppercase tracking-widest text-primary">AI Assessment Engine</p>
            <h2 className="mt-4 font-display text-4xl leading-none sm:text-5xl">YOUR PROBLEM,<br />DIAGNOSED IN MINUTES.</h2>
            <p className="mt-5 max-w-md text-muted-foreground">
              Our AI has analyzed thousands of NYC-area repair jobs. It understands building codes,
              typical contractor rates, and what&apos;s urgent vs. cosmetic.
            </p>
            <ul className="mt-6 space-y-3">
              {ASSESS_BULLETS.map((b) => (
                <li key={b} className="flex items-start gap-3 text-sm">
                  <span className="mt-0.5 text-primary">◆</span>
                  <span>{b}</span>
                </li>
              ))}
            </ul>
          </div>

          {/* Sample assessment */}
          <div className="rounded-xl border bg-card p-5 shadow-sm">
            <p className="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">
              {brand.name.toLowerCase()} — ai assessment engine
            </p>
            <div className="mt-4 rounded-lg bg-secondary p-4 text-sm">
              “My kitchen ceiling has a water stain that appeared overnight — about 8 inches across,
              brownish ring. Second floor bathroom is directly above it.”
            </div>
            <div className="mt-4 space-y-3">
              <div>
                <p className="font-mono text-[11px] uppercase tracking-widest text-primary">AI Assessment</p>
                <p className="mt-1 text-sm">
                  Likely a slow leak from the bathroom above — supply line, wax ring, or drain gasket.
                  Urgency: Medium-High. Water damage compounds fast.
                </p>
              </div>
              <div>
                <p className="font-mono text-[11px] uppercase tracking-widest text-primary">Cost Estimate</p>
                <p className="mt-1 text-sm">
                  Plumbing diagnosis + repair: $180–$450. Ceiling drywall patch if needed: +$150–$300.
                  <span className="font-semibold"> Total range: $180–$750.</span>
                </p>
              </div>
            </div>
            <div className="mt-5 flex items-center justify-between border-t pt-4">
              <span className="text-xs text-muted-foreground">Ready to receive contractor bids?</span>
              <Link href="/register"><Button size="sm">Post Job →</Button></Link>
            </div>
          </div>
        </div>
      </section>

      {/* For homeowners */}
      <section className="mx-auto max-w-6xl px-5 py-20">
        <p className="font-mono text-xs uppercase tracking-widest text-primary">For Homeowners</p>
        <h2 className="mt-4 font-display text-4xl leading-none sm:text-5xl">FREE TO POST.<br />FAIR TO PAY.</h2>
        <p className="mt-5 max-w-xl text-muted-foreground">
          Post your repair for free. Get an AI breakdown. Receive bids from background-checked local
          contractors. A small booking fee is charged only when you confirm — and it&apos;s credited off
          your final bill.
        </p>
        <div className="mt-8 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {FEATURES.map((f) => (
            <div key={f.t} className="rounded-lg border bg-card p-5">
              <h3 className="font-display text-xl uppercase">{f.t}</h3>
              <p className="mt-1 text-sm text-muted-foreground">{f.d}</p>
            </div>
          ))}
        </div>
        <div className="mt-8">
          <Link href="/register"><Button size="lg">Post Your Repair — It&apos;s Free</Button></Link>
        </div>

        {/* Stats */}
        <div className="mt-14 grid grid-cols-2 gap-6 border-t pt-10 sm:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.l}>
              <div className="font-display text-5xl text-primary" style={{ fontVariantNumeric: "tabular-nums" }}>{s.v}</div>
              <div className="mt-1 font-mono text-[11px] uppercase tracking-widest text-muted-foreground">{s.l}</div>
            </div>
          ))}
        </div>
      </section>

      {/* Reviews */}
      <section className="border-t bg-secondary">
        <div className="mx-auto max-w-6xl px-5 py-20">
          <div className="mb-12 flex flex-wrap items-end justify-between gap-4">
            <p className="font-mono text-xs uppercase tracking-widest text-primary">Reviews</p>
            <h2 className="font-display text-4xl leading-none sm:text-5xl">REAL PEOPLE.<br />REAL STORIES.</h2>
          </div>
          <div className="grid gap-6 md:grid-cols-2">
            {REVIEWS.map((r) => (
              <figure key={r.n} className="flex flex-col rounded-lg border bg-card p-6">
                <blockquote className="text-lg leading-snug">“{r.q}”</blockquote>
                <figcaption className="mt-5 flex items-end justify-between gap-4 border-t pt-4">
                  <div>
                    <div className="font-semibold">{r.n}</div>
                    <div className="text-sm text-muted-foreground">{r.loc}</div>
                  </div>
                  <div className="text-right font-mono text-xs uppercase tracking-wide text-primary">{r.saved}</div>
                </figcaption>
              </figure>
            ))}
          </div>
        </div>
      </section>

      {/* Closing CTA */}
      <section className="border-t">
        <div className="mx-auto flex max-w-6xl flex-col items-start gap-6 px-5 py-16">
          <h2 className="font-display text-5xl leading-none sm:text-6xl">STOP GOOGLING<br />CONTRACTORS.</h2>
          <p className="max-w-lg text-muted-foreground">
            Start getting real bids from vetted pros in your neighborhood. Free to post. No
            subscription ever.
          </p>
          <Link href="/register"><Button size="lg">Post Your Repair — It&apos;s Free</Button></Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t bg-foreground text-background">
        <div className="mx-auto flex max-w-6xl flex-col gap-6 px-5 py-12 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="font-display text-2xl tracking-tight">
              FIX<span className="text-primary">BRIDGE</span> AI
            </div>
            <p className="mt-2 max-w-xs text-sm text-background/70">{brand.tagline}</p>
          </div>
          <div className="flex flex-col gap-3 text-sm text-background/70 sm:items-end">
            <div className="flex gap-5">
              <a href="#" className="hover:text-background">Privacy Policy</a>
              <a href="#" className="hover:text-background">Terms of Service</a>
              <a href="#" className="hover:text-background">Contractor Agreement</a>
            </div>
            <div className="font-mono text-[11px] uppercase tracking-widest text-background/50">
              {brand.region} · Est. {brand.established}
            </div>
          </div>
        </div>
      </footer>

      {/* Loading splash + corner assistant */}
      <MascotAssistant />
    </div>
  );
}
