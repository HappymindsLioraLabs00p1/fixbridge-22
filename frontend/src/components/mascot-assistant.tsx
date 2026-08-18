"use client";

import { useEffect, useRef, useState } from "react";

const GREETING = "Don't worry — I'll fix it!";
const TAGLINE = "We Fix. You Relax.";

/**
 * App intro + corner assistant. On load it shows a brief splash — the FixBridge handyman centered,
 * wrench raised — for ~2s, then he flies to the bottom-right corner and greets the visitor out loud
 * (Web Speech API). Tapping him throws a thumbs-up.
 *
 * The character is drawn from the FixBridge mascot sheet: navy bib overalls over a navy polo, orange
 * work gloves, curly black hair, full beard, house-and-wrench chest logo. Original artwork — the
 * palette is the app's own brand tokens rather than a separate mascot palette, so he cannot drift out
 * of step with the rest of the UI.
 */
export function MascotAssistant() {
  const [mounted, setMounted] = useState(false);
  const [phase, setPhase] = useState<"intro" | "corner">("intro");
  const [bubble, setBubble] = useState(false);
  const [cheer, setCheer] = useState(false);

  // The greeting is fixed bottom-right, so on a phone it sits over the page's own buttons. Let it
  // say hello, then retire itself; tapping the mascot brings it back.
  useEffect(() => {
    if (!bubble) return;
    const timer = setTimeout(() => setBubble(false), 6000);
    return () => clearTimeout(timer);
  }, [bubble]);

  // The thumbs-up is a reaction, not a resting pose — hold it just long enough to read.
  useEffect(() => {
    if (!cheer) return;
    const timer = setTimeout(() => setCheer(false), 1600);
    return () => clearTimeout(timer);
  }, [cheer]);

  const spokenOnce = useRef(false);

  function speak() {
    if (typeof window === "undefined" || !("speechSynthesis" in window)) return;
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(GREETING);
      u.pitch = 1.9;
      u.rate = 1.05;
      const voices = window.speechSynthesis.getVoices();
      const fun = voices.find((v) => /(google uk english female|samantha|karen|zira|novelty|junior|bells)/i.test(v.name));
      if (fun) u.voice = fun;
      window.speechSynthesis.speak(u);
    } catch {
      /* no speech available — the bubble still shows the greeting */
    }
  }

  useEffect(() => {
    setMounted(true);

    // Browsers block audio until a gesture — greet on the first interaction as a fallback.
    const onGesture = () => {
      if (!spokenOnce.current) {
        spokenOnce.current = true;
        speak();
      }
      window.removeEventListener("pointerdown", onGesture);
    };
    window.addEventListener("pointerdown", onGesture);

    // Play the full splash only once per browser session; on later visits go straight to the corner.
    let toCorner: ReturnType<typeof setTimeout> | undefined;
    let greet: ReturnType<typeof setTimeout> | undefined;
    let seen = false;
    try {
      seen = sessionStorage.getItem("fb-splash-seen") === "1";
      sessionStorage.setItem("fb-splash-seen", "1");
    } catch {
      /* storage unavailable — fall back to always showing the splash */
    }
    if (seen) {
      setPhase("corner");
      setBubble(true);
    } else {
      toCorner = setTimeout(() => setPhase("corner"), 2000);
      greet = setTimeout(() => {
        setBubble(true);
        speak();
      }, 2700);
    }

    return () => {
      if (toCorner) clearTimeout(toCorner);
      if (greet) clearTimeout(greet);
      window.removeEventListener("pointerdown", onGesture);
    };
  }, []);

  if (!mounted) return null;
  const intro = phase === "intro";
  const pose: Pose = intro ? "intro" : cheer ? "cheer" : "corner";

  return (
    <>
      {/* Splash backdrop */}
      <div
        aria-hidden={!intro}
        className="fixed inset-0 z-[60] flex items-center justify-center transition-opacity duration-500"
        style={{
          background: "var(--background)",
          opacity: intro ? 1 : 0,
          pointerEvents: intro ? "auto" : "none",
        }}
      >
        <div
          className="flex flex-col items-center gap-6 transition-opacity duration-300"
          style={{ opacity: intro ? 1 : 0 }}
        >
          {/* Spacer keeps the caption below where the flying mascot sits. He is ~106px tall at the
              1.95 splash scale — a little over 200px — and the column is centred, so half of any
              extra height here is what actually pushes the caption clear of his boots. 150px left
              him sitting on the text. */}
          <div style={{ height: 250 }} aria-hidden />
          <p className="font-display text-3xl tracking-tight">Gearing up to fix things…</p>
          <div className="flex gap-1.5" aria-hidden>
            {[0, 1, 2].map((i) => (
              <span
                key={i}
                className="h-2.5 w-2.5 rounded-full"
                style={{
                  background: "var(--primary)",
                  animation: `fb-bob 0.9s ease-in-out ${i * 0.15}s infinite`,
                }}
              />
            ))}
          </div>
        </div>
      </div>

      {/* The mascot: anchored bottom-right, flown to center during the intro, then transitioned home. */}
      <div
        className="fb-mascot-move fixed bottom-[calc(1.25rem+env(safe-area-inset-bottom))] right-5 z-[70] flex select-none flex-col items-end gap-3"
        style={{
          transform: intro
            ? "translate(calc(-50vw + 86px), calc(-50vh + 44px)) scale(1.95)"
            : "none",
        }}
      >
        {bubble && !intro && (
          <div className="fb-bubble relative max-w-[190px] rounded-2xl border bg-card px-4 py-3 shadow-xl sm:max-w-[230px]">
            <p className="font-display text-lg leading-tight">{GREETING}</p>
            <p className="mt-0.5 text-xs text-muted-foreground">{TAGLINE}</p>
            <span className="absolute -bottom-1.5 right-7 h-3 w-3 rotate-45 border-b border-r bg-card" />
            <button
              onClick={() => setBubble(false)}
              aria-label="Dismiss"
              className="absolute -right-2 -top-2 flex h-6 w-6 items-center justify-center rounded-full border bg-card text-xs text-muted-foreground shadow hover:bg-muted"
            >
              ✕
            </button>
          </div>
        )}

        <button
          type="button"
          aria-label="FixBridge assistant — don't worry, I'll fix it"
          onClick={() => {
            if (!intro) {
              setBubble(true);
              setCheer(true);
              speak();
            }
          }}
          className={intro ? "" : "fb-pop"}
        >
          <div className={intro ? "fb-ready drop-shadow-2xl" : "fb-bob drop-shadow-xl"}>
            <MascotSvg pose={pose} />
          </div>
        </button>
      </div>
    </>
  );
}

type Pose = "intro" | "corner" | "cheer";

/**
 * The handyman himself, waist-up.
 *
 * The viewBox is deliberately unchanged from the previous mascot: the splash centring offsets above
 * were tuned against this width, so altering it would send him flying to the wrong place.
 */
function MascotSvg({ pose }: { pose: Pose }) {
  const NAVY = "#071a3d";       // bib overalls — the brand's navy
  const NAVY_SOFT = "#12294f";  // polo shirt and sleeves, one step lighter so the bib reads separately
  const ORANGE = "#ff6b00";     // work gloves and the logo mark
  const SKIN = "#e8b98d";
  const SKIN_SHADE = "#cf9666";
  const HAIR = "#1c1410";
  const BRASS = "#c98a3c";
  const STEEL = "#c7ccd4";

  const intro = pose === "intro";

  return (
    <svg
      viewBox="0 0 120 116"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label="The FixBridge handyman in navy overalls and orange gloves"
      // Only the corner mascot shrinks on a phone. At 110px he took a quarter of a 390px screen and
      // sat across the page copy; two-thirds of that still reads as a character while leaving the
      // text legible. The intro keeps its full size — he owns the screen at that point.
      className={intro ? "h-[106px] w-[110px]" : "h-auto w-[72px] sm:w-[110px]"}
    >
      {/* ---- Left arm (viewer-left): raised with a wrench on the splash, resting otherwise ---- */}
      {intro ? (
        <g>
          <path d="M33 72C21 70 15 59 14 47" stroke={NAVY_SOFT} strokeWidth="11" strokeLinecap="round" fill="none" />
          {/* wrench, held aloft */}
          <g transform="translate(14 40) rotate(-20)">
            <rect x="-3" y="-14" width="6" height="20" rx="2" fill={STEEL} />
            <path d="M-6.5-16a6.5 6.5 0 1010 6.5l-3.4-2.2a2.7 2.7 0 11-3.2-3.2z" fill="#9aa2ad" />
          </g>
          <circle cx="14" cy="46" r="7.5" fill={ORANGE} />
        </g>
      ) : (
        // The forearm is routed wide of the torso on purpose. Tucked in tight it vanished behind the
        // body — same navy, no edge — and left the orange glove floating as a detached blob.
        <g>
          <path d="M34 70C24 75 18 85 17 97" stroke={NAVY_SOFT} strokeWidth="11" strokeLinecap="round" fill="none" />
          <circle cx="17" cy="100" r="7.5" fill={ORANGE} />
        </g>
      )}

      {/* ---- Right arm (viewer-right): waving, or a big thumbs-up when cheering ---- */}
      {pose === "cheer" ? (
        <g>
          <path d="M87 72c10-5 15-14 15-24" stroke={NAVY_SOFT} strokeWidth="11" strokeLinecap="round" fill="none" />
          {/* Fist with the thumb up its inner edge. Centred on top of the fist it read as a pointing
              finger — the thumb has to sit beside the knuckles for the gesture to be legible. */}
          <circle cx="104" cy="47" r="9" fill={ORANGE} />
          <rect x="95" y="30" width="6.5" height="14" rx="3.2" fill={ORANGE} />
          {/* curled fingers */}
          <g stroke="#d15a00" strokeWidth="1.5" strokeLinecap="round" opacity="0.8">
            <path d="M100.5 44.5h8" />
            <path d="M101 49.5h7.5" />
          </g>
          {/* approval sparks */}
          <g stroke={ORANGE} strokeWidth="2.2" strokeLinecap="round" opacity="0.85">
            <path d="M112 30l4.5-4" />
            <path d="M115 38l5-1" />
            <path d="M106 22l1.5-5" />
          </g>
        </g>
      ) : (
        <g className="fb-wave" style={{ transformOrigin: "88px 68px" }}>
          <path d="M87 72c11-4 17-13 17-23" stroke={NAVY_SOFT} strokeWidth="11" strokeLinecap="round" fill="none" />
          <circle cx="105" cy="45" r="8" fill={ORANGE} />
          {/* three fingers, so the glove reads as an open waving hand rather than a mitt */}
          <g stroke={ORANGE} strokeWidth="3.6" strokeLinecap="round">
            <path d="M102 38v-5" />
            <path d="M107 38v-6" />
            <path d="M111 41l3-4" />
          </g>
        </g>
      )}

      {/* ---- Torso: polo shirt, then the bib overalls on top ---- */}
      <path d="M26 116C24 84 34 62 60 62s36 22 34 54z" fill={NAVY_SOFT} />

      {/* neck, tucked behind the collar */}
      <rect x="53" y="54" width="14" height="12" rx="4" fill={SKIN_SHADE} />

      {/* collar */}
      <path d="M48 62l12 11 12-11-4-1.5-8 7-8-7z" fill={NAVY} />

      {/* bib panel */}
      <path d="M45 78h30a3 3 0 013 3v35H42V81a3 3 0 013-3z" fill={NAVY} />
      {/* straps over the shoulders */}
      <path d="M48 79l-5-13" stroke={NAVY} strokeWidth="6.5" strokeLinecap="round" />
      <path d="M72 79l5-13" stroke={NAVY} strokeWidth="6.5" strokeLinecap="round" />
      {/* brass buckles where the straps meet the bib */}
      <rect x="44.5" y="74" width="7" height="6" rx="1.5" fill={BRASS} />
      <rect x="68.5" y="74" width="7" height="6" rx="1.5" fill={BRASS} />

      {/* ---- Chest logo: house mark + wordmark ---- */}
      <g className="fb-logo-glow">
        <path d="M45 95l6.5-5.5L58 95v6.5H45z" fill={ORANGE} />
        <rect x="49.5" y="96.5" width="4" height="4" rx="0.6" fill={NAVY} />
        <text x="61" y="96" fontFamily="Poppins, ui-sans-serif, sans-serif" fontWeight="800" fontSize="9" letterSpacing="0.2" fill="#ffffff">
          FIX
        </text>
        <text x="61" y="103" fontFamily="Poppins, ui-sans-serif, sans-serif" fontWeight="700" fontSize="6.2" letterSpacing="0.3" fill={ORANGE}>
          BRIDGE
        </text>
      </g>

      {/* ---- Tool belt at the waist ---- */}
      <path d="M26 107h68v7H26z" fill="#8a5a34" />
      <rect x="54" y="106" width="12" height="9" rx="1.5" fill={BRASS} />

      {/* ---- Head ---- */}
      <circle cx="41" cy="41" r="4.5" fill={SKIN} />
      <circle cx="79" cy="41" r="4.5" fill={SKIN} />
      <circle cx="60" cy="39" r="20" fill={SKIN} />

      {/* beard: hugs the jaw and leaves the cheeks bare, so he reads as friendly rather than hidden */}
      <path d="M41 36q0 25 19 25t19-25q-4 13-19 13T41 36z" fill={HAIR} />

      {/* curly hair — a base cap of hair plus curl bumps around the crown */}
      <path d="M40 36c-1-16 8-25 20-25s21 9 20 25c-2-7-4-10-7-12-3 3-7 4-10 3-4 2-9 2-13-1-4 2-8 4-10 10z" fill={HAIR} />
      <g fill={HAIR}>
        <circle cx="47" cy="19" r="7" />
        <circle cx="56" cy="14" r="7.5" />
        <circle cx="66" cy="15" r="7" />
        <circle cx="74" cy="21" r="6.5" />
        <circle cx="41" cy="30" r="5.5" />
        <circle cx="79" cy="30" r="5.5" />
      </g>

      {/* eyebrows — the main carrier of the friendly expression */}
      <path d="M48 33q5-3.5 10-0.5" stroke={HAIR} strokeWidth="2.8" strokeLinecap="round" fill="none" />
      <path d="M62 32.5q5-3 10 0.5" stroke={HAIR} strokeWidth="2.8" strokeLinecap="round" fill="none" />

      {/* eyes; the right one winks on a slow cycle */}
      <circle cx="53" cy="40" r="2.9" fill="#20222b" />
      <circle cx="54" cy="39" r="1" fill="#ffffff" />
      <g className="fb-wink" style={{ transformOrigin: "67px 40px" }}>
        <circle cx="67" cy="40" r="2.9" fill="#20222b" />
        <circle cx="68" cy="39" r="1" fill="#ffffff" />
      </g>

      {/* nose */}
      <path d="M60 40q2.5 4 -0.5 5.5" stroke={SKIN_SHADE} strokeWidth="2" strokeLinecap="round" fill="none" />

      {/* moustache, then the smile beneath it */}
      <path d="M51.5 48q8.5-4.5 17 0-4.5 4.5-8.5 4.5T51.5 48z" fill={HAIR} />
      <path d="M53 53q7 6.5 14 0z" fill="#ffffff" />
      <path d="M53 53q7 6.5 14 0" stroke="#3a2318" strokeWidth="1.6" fill="none" strokeLinecap="round" />

      {/* cheeks */}
      <circle cx="46.5" cy="47" r="3.4" fill="#ff9a76" opacity="0.5" />
      <circle cx="73.5" cy="47" r="3.4" fill="#ff9a76" opacity="0.5" />
    </svg>
  );
}
