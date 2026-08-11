# FixBridge on Google Play — release guide

**What this is:** publishing the existing PWA to the Play Store as a Trusted Web Activity (TWA) —
a thin Android wrapper around the live site. No rewrite, no second codebase. The app ships whatever
is deployed at the URL, so a web deploy updates the app without a store review.

**What this is not:** a route to the Apple App Store. See §7.

**Who does what:** the steps below need your Play Console account, your identity documents and your
signing key. I have prepared the repository side; the account, payment and submission steps are
yours to perform and cannot be delegated.

---

## 1. What is already done

| Requirement | Status |
|---|---|
| Web app manifest with `name`, `short_name`, `start_url`, `display: standalone` | ✅ `frontend/src/app/manifest.ts` |
| 192px and 512px icons, plus a maskable 512px | ✅ verified at those exact dimensions |
| Service worker with a fetch handler | ✅ `/sw.js` returns 200 |
| HTTPS on a stable domain | ✅ `fixbridge-22.vercel.app` |
| Digital Asset Links endpoint | ✅ `/.well-known/assetlinks.json`, driven by env vars |
| Bubblewrap config | ✅ `android/twa-manifest.json` |

The asset-links endpoint returns `[]` until you set the fingerprint, which is the honest answer
before an app exists. Nothing breaks; verification simply hasn't been granted yet.

---

## 2. Prerequisites you need

- A **Google Play Developer account** — $25 once, at <https://play.google.com/console/signup>.
  Identity verification takes anywhere from a day to two weeks, so start this first.
- **JDK 17+** and **Node 18+** locally. You already have both (JDK 21 is installed).
- A **privacy policy at a public URL**. Play rejects submissions without one, and FixBridge handles
  names, addresses, photos and payment data, so this is not a formality.

---

## 3. Generate the Android project

```bash
npx @bubblewrap/cli init --manifest https://fixbridge-22.vercel.app/manifest.webmanifest
```

Bubblewrap will download the Android SDK on first run and ask you to confirm the values. The
prepared `android/twa-manifest.json` has the answers already — copy it into the project directory
if you would rather not retype them. The one that matters is the **package name**, `ai.fixbridge.app`:
it is permanent and cannot be changed after the first upload.

When prompted to create a signing key, let it create one. **Back up the generated keystore and its
password somewhere durable** — losing it means you can never update the app under this listing.
Do not commit it; `android/*.keystore` is gitignored.

Then build:

```bash
npx @bubblewrap/cli build
```

This produces `app-release-bundle.aab` — the file you upload.

---

## 4. First upload

1. Play Console → **Create app**. Name "FixBridge", type App, free.
2. **Release → Testing → Internal testing → Create new release.**
3. Upload the `.aab`. Add yourself as a tester.

Do the first release as **internal testing**, not production. Internal testing is available in
minutes; production review takes days. You want to see the app on a real phone before strangers do.

---

## 5. Complete the asset-links handshake

This is the step people get wrong, and the symptom is subtle: the app works but shows a browser
address bar across the top.

New apps are enrolled in **Play App Signing**, so Google re-signs your bundle. The fingerprint
Android checks is **Google's, not your local keystore's**. Using the local one is the usual reason
verification fails silently.

1. Play Console → **Setup → App signing**.
2. Copy the **SHA-256 certificate fingerprint** (colon-separated, uppercase).
3. In Vercel → Project → Settings → Environment Variables, add:

   ```
   ANDROID_CERT_FINGERPRINT = <the SHA-256 from Play Console>
   ANDROID_PACKAGE_NAME     = ai.fixbridge.app
   ```

4. Redeploy the frontend.
5. Confirm it is live:

   ```bash
   curl https://fixbridge-22.vercel.app/.well-known/assetlinks.json
   ```

   It must show your package name and fingerprint, not `[]`.

6. Reinstall the app. The address bar should be gone.

---

## 6. Store listing

Play will not let you publish until each of these exists:

- Short description (80 chars) and full description (4000 chars)
- **Feature graphic**, 1024×500 — required, and not auto-generated
- At least 2 phone screenshots (min 320px, max 3840px on the long edge)
- App icon 512×512 — reuse `public/icons/icon-512.png`
- Privacy policy URL
- **Data safety form** — declare that you collect names, addresses, photos, location and payment
  information. Filling this in inaccurately is grounds for suspension.
- Content rating questionnaire

---

## 7. Why the App Store is not on this list

Apple's **Guideline 4.2 (Minimum Functionality)** rejects apps that are repackaged websites. A TWA
is exactly that pattern, and there is no iOS equivalent that Apple accepts. Reaching the App Store
means building a genuine native app — the React Native project the mobile brief assumed — plus a
Mac with Xcode and an Apple Developer account at $99/year.

That is a separate project measured in weeks, not a packaging step.

---

## 8. Do these before anyone else installs it

1. 🔴 **Rotate the Neon database password.** It was pasted into a chat transcript and is still live.
2. 🔴 **Change the admin password** at `/account`. `password123` on a publicly reachable site.
3. ⬜ **Click through the app on a phone.** The chat screen, voice input, contractor list and rating
   panel are compile-verified but never human-verified.
4. ⬜ **Deal with the Render cold start.** The free tier spins down when idle; the first request
   after a quiet period took several attempts and over a minute to answer. A user who installs the
   app and waits 60 seconds for the first screen will uninstall it. Either move the backend to a
   paid always-on instance, or add a keep-alive ping.

Item 4 is the one most likely to sink the launch, and it is not a code problem — it is a hosting
plan decision.
