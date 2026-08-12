"use client";

import { useEffect, useState } from "react";

/**
 * Whether React has taken over the server-rendered markup.
 *
 * <p>Until it has, a `type="submit"` button is still a plain HTML submit control: clicking it makes
 * the browser perform its own GET, the page reloads to `?`, and everything typed is discarded. The
 * `onSubmit` handler that would have called `preventDefault` isn't attached yet, so nothing stops
 * it and nothing reports it — the user sees a flicker and an empty form.
 *
 * <p>On a fast connection the window is a few hundred milliseconds and nobody notices. On a slow
 * phone, or when the page is waiting on a cold backend, it is long enough to lose a sign-up — which
 * is exactly what it looked like: "registration is slow and sometimes does nothing".
 *
 * <p>Gating the submit button on this closes the window. The cost is a button that is briefly
 * disabled on load, which is honest: the form genuinely cannot be submitted yet.
 */
export function useHydrated(): boolean {
  const [hydrated, setHydrated] = useState(false);
  // Effects only run on the client and only after hydration, which is precisely the signal needed.
  useEffect(() => setHydrated(true), []);
  return hydrated;
}
