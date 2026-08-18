"use client";

import { useEffect, useRef } from "react";

/**
 * Six one-digit boxes that behave like a single code field.
 *
 * <p>The interactions that matter were each added for a person who hits them daily: paste drops a
 * whole code across the boxes (codes arrive by SMS and get copied); iOS/Android keyboard
 * suggestions fill via {@code autocomplete="one-time-code"} on the first box; backspace in an empty
 * box walks left; typing walks right; and every box raises the numeric keypad. When the sixth digit
 * lands, {@code onComplete} fires so the customer never has to find a Verify button after doing the
 * only thing the screen asked of them.
 */
export function OtpInput({
  value,
  onChange,
  onComplete,
  disabled,
  error,
}: {
  value: string;
  onChange: (code: string) => void;
  onComplete?: (code: string) => void;
  disabled?: boolean;
  error?: boolean;
}) {
  const LENGTH = 6;
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = value.padEnd(LENGTH, " ").slice(0, LENGTH).split("");

  // Focus follows the first empty box, so "tap anywhere, type" just works.
  useEffect(() => {
    if (disabled) return;
    const firstEmpty = Math.min(value.length, LENGTH - 1);
    refs.current[firstEmpty]?.focus();
  }, [value, disabled]);

  function commit(next: string) {
    const clean = next.replace(/\D/g, "").slice(0, LENGTH);
    onChange(clean);
    if (clean.length === LENGTH) onComplete?.(clean);
  }

  return (
    <div
      className="flex justify-center gap-2"
      role="group"
      aria-label="6-digit verification code"
      onPaste={(e) => {
        e.preventDefault();
        commit(e.clipboardData.getData("text"));
      }}
    >
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          maxLength={1}
          // The platform's SMS-autofill hook. Only meaningful on the first box; harmless elsewhere.
          autoComplete={i === 0 ? "one-time-code" : "off"}
          disabled={disabled}
          value={d.trim()}
          aria-label={`Digit ${i + 1} of ${LENGTH}`}
          aria-invalid={error || undefined}
          onChange={(e) => {
            const typed = e.target.value.replace(/\D/g, "");
            // A multi-digit change here is keyboard autofill delivering the whole code at once.
            if (typed.length > 1) return commit(typed);
            commit(value.slice(0, i) + typed + value.slice(i + 1));
          }}
          onKeyDown={(e) => {
            if (e.key === "Backspace" && !digits[i].trim() && i > 0) {
              e.preventDefault();
              commit(value.slice(0, i - 1));
            }
            if (e.key === "ArrowLeft" && i > 0) refs.current[i - 1]?.focus();
            if (e.key === "ArrowRight" && i < LENGTH - 1) refs.current[i + 1]?.focus();
          }}
          className={`h-13 w-11 rounded-xl border-2 bg-card text-center font-display text-xl tabular
            outline-none transition-colors
            ${error ? "border-destructive" : "border-border focus:border-primary"}
            disabled:opacity-50 sm:h-14 sm:w-12`}
        />
      ))}
    </div>
  );
}
