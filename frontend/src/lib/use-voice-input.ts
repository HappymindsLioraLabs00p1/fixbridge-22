"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Speech-to-text via the browser's own recogniser.
 *
 * Someone describing a leak is often holding a torch in the other hand, so typing is the awkward
 * option exactly when they most need help. This uses the Web Speech API rather than shipping audio
 * to a server: it costs nothing, adds no latency, and the recording never leaves the device.
 *
 * Support is uneven — Safari and Chrome have it, Firefox does not — so `supported` is exposed and
 * the caller hides the control rather than offering a button that silently does nothing.
 */

// Minimal shape of the vendor-prefixed API. The DOM lib doesn't declare it.
interface SpeechRecognitionAlternativeLike { transcript: string }
interface SpeechRecognitionResultLike {
  readonly length: number;
  isFinal: boolean;
  [index: number]: SpeechRecognitionAlternativeLike;
}
interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: { readonly length: number; [index: number]: SpeechRecognitionResultLike };
}
interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start(): void;
  stop(): void;
  abort(): void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
}
type RecognitionCtor = new () => SpeechRecognitionLike;

function recogniser(): RecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as {
    SpeechRecognition?: RecognitionCtor;
    webkitSpeechRecognition?: RecognitionCtor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export interface VoiceInput {
  supported: boolean;
  listening: boolean;
  /** What has been heard so far this session, including the in-progress phrase. */
  transcript: string;
  error: string | null;
  start: () => void;
  stop: () => void;
}

export function useVoiceInput(onFinal?: (text: string) => void): VoiceInput {
  const [supported, setSupported] = useState(false);
  const [listening, setListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [error, setError] = useState<string | null>(null);

  const ref = useRef<SpeechRecognitionLike | null>(null);
  // Held in a ref so restarting recognition doesn't rebind a stale callback.
  const finalHandler = useRef(onFinal);
  finalHandler.current = onFinal;

  // Detection happens after mount: on the server there is no window, and rendering the button
  // during SSR and then removing it would flash a control that doesn't work.
  useEffect(() => setSupported(recogniser() !== null), []);

  const stop = useCallback(() => {
    ref.current?.stop();
    setListening(false);
  }, []);

  const start = useCallback(() => {
    const Ctor = recogniser();
    if (!Ctor) return;

    ref.current?.abort();
    setError(null);
    setTranscript("");

    const rec = new Ctor();
    rec.lang = navigator.language || "en-US";
    // Interim results let the text appear as it's spoken; without them the field stays empty until
    // the speaker stops, which reads as broken.
    rec.interimResults = true;
    rec.continuous = true;

    rec.onresult = (e) => {
      let text = "";
      for (let i = 0; i < e.results.length; i++) text += e.results[i][0].transcript;
      setTranscript(text);
      if (e.results[e.results.length - 1]?.isFinal) finalHandler.current?.(text.trim());
    };
    rec.onerror = (e) => {
      // A denied microphone is the one worth explaining; the rest are transient.
      setError(e.error === "not-allowed"
        ? "Microphone access was blocked. You can still type."
        : "I couldn't hear that — try again, or type instead.");
      setListening(false);
    };
    rec.onend = () => setListening(false);

    ref.current = rec;
    rec.start();
    setListening(true);
  }, []);

  // Recognition holds the microphone open, so it has to be released when the screen goes away.
  useEffect(() => () => ref.current?.abort(), []);

  return { supported, listening, transcript, error, start, stop };
}
