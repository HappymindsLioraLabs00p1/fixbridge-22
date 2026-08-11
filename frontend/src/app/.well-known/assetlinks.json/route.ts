import { NextResponse } from "next/server";

/**
 * Digital Asset Links — the proof that this website and the Android app are the same owner.
 *
 * <p>Without it a Trusted Web Activity still runs, but Android shows the browser address bar across
 * the top, which is exactly the "it's just a website" look the store listing is meant to avoid.
 * Android fetches this file over HTTPS at first launch and caches the result.
 *
 * <p>The fingerprint comes from an environment variable rather than being committed, because it is
 * not known until the first build is uploaded. New apps are enrolled in Play App Signing, so Google
 * re-signs the bundle and the fingerprint Android checks is *Google's*, not the local keystore's —
 * it appears in Play Console under Setup → App signing once the first release is uploaded. Putting
 * a local keystore fingerprint here is the usual reason verification silently fails.
 *
 * <p>The fingerprint is public information, not a secret. It lives in config only so it can be set
 * after deployment without a code change.
 */

export const dynamic = "force-static";

export function GET() {
  const fingerprint = process.env.ANDROID_CERT_FINGERPRINT?.trim();
  const packageName = process.env.ANDROID_PACKAGE_NAME?.trim() || "ai.fixbridge.app";

  // Serving an empty array is correct when the app isn't published yet: Android reads it as "no
  // app is authorised", which is true. Serving a malformed file would make the failure harder to
  // diagnose than serving an honest empty one.
  if (!fingerprint) {
    return NextResponse.json([], {
      headers: { "content-type": "application/json" },
    });
  }

  return NextResponse.json(
    [
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: packageName,
          // Play Console shows this colon-separated and uppercase; that is the expected format.
          sha256_cert_fingerprints: fingerprint.split(",").map((f) => f.trim()),
        },
      },
    ],
    { headers: { "content-type": "application/json" } },
  );
}
