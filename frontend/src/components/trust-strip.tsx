/**
 * The five promises, stated once.
 *
 * <p>Every claim here is one the product actually keeps and can be checked in the code: dispatch
 * refuses a contractor without current licence and insurance, the customer is quoted one retail
 * figure with the contractor's net never shown, and a payout is gated on the customer signing the
 * work off. A trust strip that promises something the system does not enforce is worse than none.
 */
const PROMISES = [
  {
    title: "Licensed & verified",
    body: "Every pro's licence and insurance is checked before we send them.",
    icon: "M12 3l7 3v6c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6l7-3Zm-1.2 11.4 4.6-4.6-1.2-1.2-3.4 3.4-1.6-1.6-1.2 1.2 2.8 2.8Z",
  },
  {
    title: "Upfront pricing",
    body: "One clear price before you commit. No hidden fees.",
    icon: "M12 2v20M17 6.5c0-1.9-2.2-3-5-3s-5 1.1-5 3 2.2 2.6 5 3.2 5 1.3 5 3.3-2.2 3-5 3-5-1.1-5-3",
  },
  {
    title: "Local experts",
    body: "Pros who work your area and know its buildings.",
    icon: "M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11Zm0-8.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5Z",
  },
  {
    title: "Tracked in real time",
    body: "See who's coming and where the job has got to.",
    icon: "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20Zm1-10.4V6h-2v6.4l4.2 2.5 1-1.7-3.2-1.6Z",
  },
  {
    title: "Paid on completion",
    body: "Your pro is paid only after you confirm the work.",
    icon: "M3 6h18v12H3zM3 10h18M7 15h4",
  },
];

export function TrustStrip() {
  return (
    <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
      {PROMISES.map((p) => (
        <li key={p.title} className="rounded-xl border bg-card p-4">
          <span className="grid h-9 w-9 place-items-center rounded-full bg-primary-subtle text-primary" aria-hidden>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"
                 strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
              <path d={p.icon} />
            </svg>
          </span>
          <p className="mt-3 text-sm font-semibold">{p.title}</p>
          <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{p.body}</p>
        </li>
      ))}
    </ul>
  );
}
