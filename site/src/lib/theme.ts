import { useCallback, useEffect, useState } from "react";

export type ThemePref = "system" | "light" | "dark";

const KEY = "incidentlab-theme";
const ORDER: ThemePref[] = ["system", "light", "dark"];

function stored(): ThemePref {
  try {
    const value = localStorage.getItem(KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

function apply(pref: ThemePref) {
  const dark = pref === "dark" || (pref === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}

export function useTheme(): { pref: ThemePref; next: () => void } {
  const [pref, setPref] = useState<ThemePref>(stored);

  useEffect(() => {
    apply(pref);
    if (pref !== "system") return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => apply("system");
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [pref]);

  const next = useCallback(() => {
    setPref((current) => {
      const value = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
      try {
        if (value === "system") localStorage.removeItem(KEY);
        else localStorage.setItem(KEY, value);
      } catch {
        /* storage can be blocked; the theme still changes for this visit */
      }
      return value;
    });
  }, []);

  return { pref, next };
}
