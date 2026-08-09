"use client";

import { useEffect } from "react";

/**
 * Registers the service worker, which is what makes Android offer "Install app".
 * Registration is deliberately deferred until after load so it never competes with the first paint.
 */
export function ServiceWorkerRegistration() {
  useEffect(() => {
    if (typeof window === "undefined" || !("serviceWorker" in navigator)) return;
    // Only in production: a worker caching a dev build causes very confusing stale-asset bugs.
    if (process.env.NODE_ENV !== "production") return;

    const register = () => {
      navigator.serviceWorker.register("/sw.js").catch(() => {
        // Installability is a bonus, not a requirement — the app works fine without it.
      });
    };
    if (document.readyState === "complete") register();
    else window.addEventListener("load", register, { once: true });
  }, []);

  return null;
}
