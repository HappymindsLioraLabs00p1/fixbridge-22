/**
 * FixBridge service worker.
 *
 * Purpose is narrow and deliberate: make the app installable and make a cold launch feel instant.
 * It is NOT an offline mode.
 *
 * The hard rule here is that /api/* is never cached. This app shows money — bids, retail prices,
 * payout state — and serving a stale figure from a cache would be worse than showing an error. Only
 * static build assets are cached, and they are content-hashed, so a new deploy fetches new files.
 */

const CACHE = "fixbridge-shell-v1";

self.addEventListener("install", (event) => {
  // Take over as soon as the new worker is ready rather than waiting for every tab to close.
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      // Drop caches from previous versions so a deploy can't leave stale assets behind.
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)));
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Only ever touch same-origin GETs.
  if (request.method !== "GET" || url.origin !== self.location.origin) return;

  // Never cache the API — a stale price or job status is a correctness bug, not a nicety.
  if (url.pathname.startsWith("/api/")) return;

  // Navigations go to the network first so a signed-in user always gets current markup; the cached
  // copy is only a fallback for a dropped connection.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE).then((c) => c.put(request, copy));
          return response;
        })
        .catch(() => caches.match(request).then((hit) => hit || caches.match("/"))),
    );
    return;
  }

  // Build assets are content-hashed, so serving them from cache is safe and makes launch instant.
  if (url.pathname.startsWith("/_next/static/") || url.pathname.startsWith("/icons/")) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ||
          fetch(request).then((response) => {
            const copy = response.clone();
            caches.open(CACHE).then((c) => c.put(request, copy));
            return response;
          }),
      ),
    );
  }
});
