import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router";
import { copyText, onCopied } from "../lib/copy";
import { lab } from "../lib/data";
import { useTheme } from "../lib/theme";
import { AutoIcon, CloseIcon, MenuIcon, MoonIcon, SearchIcon, SunIcon } from "./Icons";
import { SearchDialog } from "./SearchDialog";

const NAV = [
  { to: "/", label: "Start", end: true },
  { to: "/how-it-works", label: "How it works" },
  { to: "/guide", label: "Guide" },
  { to: "/catalog", label: "Catalog" },
  { to: "/quiz", label: "Quiz" },
  { to: "/commands", label: "Commands" },
  { to: "/repo", label: "Repo" },
];

const THEME_LABEL = { system: "Theme follows the system", light: "Light theme", dark: "Dark theme" } as const;

function ThemeButton() {
  const { pref, next } = useTheme();
  const Icon = pref === "system" ? AutoIcon : pref === "light" ? SunIcon : MoonIcon;
  return (
    <button type="button" className="icon-btn" onClick={next} aria-label={`${THEME_LABEL[pref]}. Change theme`} title={THEME_LABEL[pref]}>
      <Icon />
    </button>
  );
}

function Toast() {
  const [message, setMessage] = useState("");
  useEffect(() => {
    let timer: number | undefined;
    const off = onCopied((m) => {
      setMessage(m);
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setMessage(""), 1800);
    });
    return () => {
      off();
      window.clearTimeout(timer);
    };
  }, []);
  return (
    <div className={`toast ${message ? "is-on" : ""}`} role="status" aria-live="polite">
      {message}
    </div>
  );
}

/** Copy and in-app navigation for HTML that comes from the docs, where React cannot attach handlers. */
function useDocumentHandlers() {
  const navigate = useNavigate();
  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      const el = event.target as HTMLElement;
      const copy = el.closest<HTMLElement>("[data-copy]");
      if (copy && !copy.closest("button.copy")) {
        event.preventDefault();
        void copyText(copy.dataset.copy ?? "");
        return;
      }
      const link = el.closest<HTMLAnchorElement>(".html-content a[href^='/']");
      if (link && !event.metaKey && !event.ctrlKey && !event.shiftKey && event.button === 0) {
        event.preventDefault();
        navigate(link.getAttribute("href") ?? "/");
      }
    };
    const onKey = (event: KeyboardEvent) => {
      const el = event.target as HTMLElement;
      if ((event.key === "Enter" || event.key === " ") && el.matches("[role='button'][data-copy]")) {
        event.preventDefault();
        void copyText(el.dataset.copy ?? "");
      }
    };
    document.addEventListener("click", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("click", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [navigate]);
}

function useScrollOnNavigate() {
  const { pathname, hash } = useLocation();
  useEffect(() => {
    if (hash) return;
    window.scrollTo({ top: 0 });
  }, [pathname, hash]);
  useEffect(() => {
    if (!hash) return;
    let tries = 0;
    const find = () => {
      const el = document.getElementById(decodeURIComponent(hash.slice(1)));
      if (el) el.scrollIntoView({ block: "start" });
      else if (tries++ < 20) window.setTimeout(find, 50);
    };
    find();
  }, [pathname, hash]);
}

export function Layout() {
  const location = useLocation();
  const [menuOpenOn, setMenuOpenOn] = useState<string | null>(null);
  const menu = menuOpenOn === location.pathname;
  const setMenu = (open: (current: boolean) => boolean) => setMenuOpenOn(open(menu) ? location.pathname : null);
  const [searchOpen, setSearchOpen] = useState(false);
  useDocumentHandlers();
  useScrollOnNavigate();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const typing = (event.target as HTMLElement).closest("input, textarea, select, [contenteditable]");
      if ((event.key === "k" && (event.metaKey || event.ctrlKey)) || (event.key === "/" && !typing)) {
        event.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="shell">
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <NavLink to="/" className="brand" aria-label="incidentlab handbook, start page">
            <span className="brand-led" aria-hidden="true" />
            <span className="brand-name">incidentlab</span>
            <span className="brand-tag">handbook</span>
          </NavLink>
          <nav className={`nav ${menu ? "is-open" : ""}`} aria-label="Main">
            {NAV.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className="nav-link">
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="topbar-tools">
            <button type="button" className="search-btn" onClick={() => setSearchOpen(true)} aria-label="Search the handbook">
              <SearchIcon />
              <span className="search-btn-label">Search</span>
              <kbd>/</kbd>
            </button>
            <ThemeButton />
            <button type="button" className="icon-btn menu-btn" aria-expanded={menu} aria-label={menu ? "Close menu" : "Open menu"} onClick={() => setMenu((m) => !m)}>
              {menu ? <CloseIcon /> : <MenuIcon />}
            </button>
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="footer">
        <div className="footer-inner">
          <p>
            <strong>For training only.</strong> The services break on purpose. This handbook shows {lab.counts.scenarios} scenarios by title only: the answers stay in the CLI.
          </p>
          <p className="footer-meta">
            Built from the repo's <code>docs/</code>, <code>Makefile</code>, <code>docker-compose.yml</code> and <code>labctl export</code>.
          </p>
        </div>
      </footer>
      <SearchDialog open={searchOpen} onClose={() => setSearchOpen(false)} />
      <Toast />
    </div>
  );
}
