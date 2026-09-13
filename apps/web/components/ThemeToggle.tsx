"use client";

import { useEffect, useState } from "react";

type Theme = "dark" | "light";

/**
 * Dark is the default (docs/design/tokens.md §2). The toggle exists because the
 * prototype had one and both palettes are complete; because status is carried by
 * shape rather than hue, neither theme is load-bearing for meaning.
 *
 * The choice is stored per browser. It is a display preference, not state the
 * control plane has any business knowing.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("dark");

  useEffect(() => {
    const stored = window.localStorage.getItem("opsatlas-theme");
    if (stored === "light" || stored === "dark") {
      setTheme(stored);
      document.documentElement.dataset.theme = stored;
    }
  }, []);

  function toggle() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      window.localStorage.setItem("opsatlas-theme", next);
    } catch {
      // A blocked storage API is not a reason to refuse to change the theme.
    }
  }

  return (
    <button
      type="button"
      onClick={toggle}
      aria-pressed={theme === "light"}
      className="cursor-pointer rounded-[5px] border border-rule-2 bg-transparent px-2 py-1 text-[12.5px] text-ink-2 hover:border-ink-3 hover:text-ink"
    >
      {theme === "dark" ? "Light theme" : "Dark theme"}
    </button>
  );
}
