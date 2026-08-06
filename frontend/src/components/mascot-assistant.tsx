"use client";

import { useEffect, useRef, useState } from "react";

const GREETING = "How may I help you?";

/**
 * App intro + corner assistant. On load it shows a brief splash — the plumber mascot centered, tools
 * in hand, "getting ready to repair" — for ~2s, then he flies to the bottom-right corner and greets
 * the visitor out loud (Web Speech API). Original artwork, not a trademarked character.
 */
export function MascotAssistant() {
  const [phase, setPhase] = useState<"intro" | "corner">("intro");
  const [bubble, setBubble] = useState(false);
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
    const toCorner = setTimeout(() => setPhase("corner"), 2000);
    const greet = setTimeout(() => {
      setBubble(true);
      speak();
    }, 2700);
    // Browsers block audio until a gesture — greet on the first interaction as a fallback.
    const onGesture = () => {
      if (!spokenOnce.current) {
        spokenOnce.current = true;
        speak();
      }
      window.removeEventListener("pointerdown", onGesture);
    };
    window.addEventListener("pointerdown", onGesture);
    return () => {
      clearTimeout(toCorner);
      clearTimeout(greet);
      window.removeEventListener("pointerdown", onGesture);
    };
  }, []);

  const intro = phase === "intro";

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
          {/* spacer keeps the caption below where the flying mascot sits */}
          <div style={{ height: 150 }} aria-hidden />
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
        className="fb-mascot-move fixed bottom-5 right-5 z-[70] flex select-none flex-col items-end gap-3"
        style={{
          transform: intro
            ? "translate(calc(-50vw + 86px), calc(-50vh + 44px)) scale(1.95)"
            : "none",
        }}
      >
        {bubble && !intro && (
          <div className="fb-bubble relative max-w-[230px] rounded-2xl border bg-card px-4 py-3 shadow-xl">
            <p className="font-display text-lg leading-tight">{GREETING}</p>
            <p className="mt-0.5 text-xs text-muted-foreground">Tap me — I&apos;ll say hi 👋</p>
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
          aria-label="FixBridge assistant — how may I help you?"
          onClick={() => {
            if (!intro) {
              setBubble(true);
              speak();
            }
          }}
          className={intro ? "" : "fb-pop"}
        >
          <div className={intro ? "fb-ready drop-shadow-2xl" : "fb-bob drop-shadow-xl"}>
            <MascotSvg mode={intro ? "intro" : "corner"} />
          </div>
        </button>
      </div>
    </>
  );
}

function MascotSvg({ mode }: { mode: "intro" | "corner" }) {
  // Waist-up plumber in a blue jumpsuit + blue cap, with "FIXBRIDGE" on the uniform. Original artwork.
  const BLUE = "#2f6bb5";
  const BLUE_DARK = "#234f86";
  const SKIN = "#ffcf9e";
  return (
    <svg width="110" height="106" viewBox="0 0 120 116" xmlns="http://www.w3.org/2000/svg">
      {/* left arm holding a wrench (raised a little more when getting ready) */}
      <path
        d={mode === "intro" ? "M34 70C22 70 15 60 14 48" : "M34 70C24 76 20 90 22 102"}
        stroke={BLUE}
        strokeWidth="12"
        strokeLinecap="round"
        fill="none"
      />
      <g transform={mode === "intro" ? "translate(14 42) rotate(-24)" : "translate(20 104) rotate(18)"}>
        <rect x="-3" y="-13" width="6" height="18" rx="2" fill="#c7ccd4" />
        <path d="M-6-15a6 6 0 109 6l-3-2a2.5 2.5 0 11-3-3z" fill="#9aa2ad" />
      </g>

      {/* jumpsuit torso (cut at the waist) */}
      <path d="M24 116C22 82 33 60 60 60s36 22 34 56z" fill={BLUE} />

      {/* neck */}
      <rect x="53" y="52" width="14" height="12" rx="4" fill={SKIN} />

      {/* head + ears */}
      <circle cx="39" cy="41" r="4.5" fill={SKIN} />
      <circle cx="81" cy="41" r="4.5" fill={SKIN} />
      <circle cx="60" cy="40" r="21" fill={SKIN} />

      {/* cap */}
      <path d="M40 40C41 20 50 14 60 14s19 6 20 26z" fill={BLUE_DARK} />
      <path d="M60 44C44 44 30 44 25 49c8-3 20-4 35-4z" fill={BLUE} />
      <ellipse cx="60" cy="40" rx="20.5" ry="4" fill={BLUE_DARK} />
      <circle cx="60" cy="26" r="6" fill="#fff" />
      <path d="M57 26l2.4 2.4L64 24" stroke="#ff4d1c" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />

      {/* face */}
      <circle cx="52" cy="42" r="3.1" fill="#20222b" />
      <circle cx="68" cy="42" r="3.1" fill="#20222b" />
      <circle cx="46" cy="49" r="3.4" fill="#ff9a76" opacity="0.6" />
      <circle cx="74" cy="49" r="3.4" fill="#ff9a76" opacity="0.6" />
      <path d="M52 50q8 8 16 0" stroke="#20222b" strokeWidth="2.6" fill="none" strokeLinecap="round" />
      <path d="M53 47q7 4 14 0" stroke="#6b4a2b" strokeWidth="3" fill="none" strokeLinecap="round" />

      {/* collar + zipper */}
      <path d="M47 60l13 12 13-12-4-1-9 8-9-8z" fill={BLUE_DARK} />
      <line x1="60" y1="72" x2="60" y2="86" stroke={BLUE_DARK} strokeWidth="2.5" />
      <line x1="60" y1="101" x2="60" y2="114" stroke={BLUE_DARK} strokeWidth="2.5" />

      {/* FIXBRIDGE name patch */}
      <rect x="31" y="86" width="58" height="15" rx="3" fill="#ffffff" />
      <text x="60" y="97.5" textAnchor="middle" fontFamily="'Barlow Condensed', sans-serif" fontWeight="800" fontSize="11" letterSpacing="0.5" fill={BLUE_DARK}>
        FIXBRIDGE
      </text>

      {/* right arm — waving (corner) or raised holding a hammer (intro / getting ready) */}
      {mode === "corner" ? (
        <g className="fb-wave" style={{ transformOrigin: "88px 66px" }}>
          <path d="M88 66c10-4 16-12 16-22" stroke={BLUE} strokeWidth="12" strokeLinecap="round" fill="none" />
          <circle cx="104" cy="41" r="7.5" fill={SKIN} />
        </g>
      ) : (
        <g>
          <path d="M88 66c9-6 13-16 12-26" stroke={BLUE} strokeWidth="12" strokeLinecap="round" fill="none" />
          <circle cx="100" cy="38" r="7.5" fill={SKIN} />
          {/* hammer */}
          <g transform="translate(100 30) rotate(18)">
            <rect x="-2" y="-2" width="4" height="20" rx="1.5" fill="#8a5a34" />
            <rect x="-9" y="-9" width="18" height="9" rx="2" fill="#9aa2ad" />
            <rect x="6" y="-9" width="4" height="9" rx="1" fill="#767d88" />
          </g>
        </g>
      )}
    </svg>
  );
}
