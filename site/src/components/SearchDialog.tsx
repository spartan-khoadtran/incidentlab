import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { search, type SearchEntry } from "../lib/search";
import { SearchIcon } from "./Icons";

const PAGES: SearchEntry[] = [
  { kind: "page", title: "Start", context: "Quick start and the modes", body: "make up make new make answer quick start modes", href: "/" },
  { kind: "page", title: "How it works", context: "System, fault loop, scoring", body: "request path diagram services fault control loop daemon scoring dashboards", href: "/how-it-works" },
  { kind: "page", title: "Guide", context: "The study guide", body: "docs red use guide", href: "/guide" },
  { kind: "page", title: "Catalog", context: "Every scenario by level and topic", body: "scenarios exercises level topic toolbox", href: "/catalog" },
  { kind: "page", title: "Quiz", context: "Theory drills", body: "make quiz numbers concepts drills", href: "/quiz" },
  { kind: "page", title: "Commands", context: "Every make target", body: "make targets lifecycle exercises tools development", href: "/commands" },
  { kind: "page", title: "Repo", context: "Folders and Gradle modules", body: "repository structure gradle modules folders config", href: "/repo" },
];

const KIND_LABEL: Record<SearchEntry["kind"], string> = { page: "Page", guide: "Guide", scenario: "Scenario", command: "Command" };

export function SearchDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [entries, setEntries] = useState<SearchEntry[]>(PAGES);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const [failed, setFailed] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    const el = dialog.current;
    if (!el) return;
    if (open && !el.open) {
      el.showModal();
      input.current?.select();
      import("../generated/search.json").then(
        (m) => {
          setFailed(false);
          setEntries([...PAGES, ...(m.default as SearchEntry[])]);
        },
        () => setFailed(true),
      );
    } else if (!open && el.open) el.close();
  }, [open]);

  const hits = useMemo(() => search(entries, query), [entries, query]);
  const activeIndex = Math.min(active, Math.max(hits.length - 1, 0));

  const go = (href: string) => {
    onClose();
    navigate(href);
  };

  return (
    <dialog
      ref={dialog}
      className="search"
      aria-label="Search the handbook"
      onClose={onClose}
      onClick={(e) => {
        if (e.target === dialog.current) onClose();
      }}
    >
      <div className="search-box">
        <label className="search-field">
          <SearchIcon />
          <span className="visually-hidden">Search guide sections, scenarios and commands</span>
          <input
            ref={input}
            type="search"
            value={query}
            placeholder="Search the guide, the catalog and the commands"
            autoComplete="off"
            spellCheck={false}
            role="combobox"
            aria-expanded={hits.length > 0}
            aria-controls="search-results"
            aria-activedescendant={hits.length ? `hit-${activeIndex}` : undefined}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setActive(Math.min(activeIndex + 1, hits.length - 1));
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setActive(Math.max(activeIndex - 1, 0));
              } else if (e.key === "Enter" && hits[activeIndex]) {
                e.preventDefault();
                go(hits[activeIndex].entry.href);
              }
            }}
          />
          <kbd>esc</kbd>
        </label>
        <ul id="search-results" className="search-results" role="listbox" aria-label="Results">
          {hits.map((hit, i) => (
            <li key={`${hit.entry.href}-${i}`} id={`hit-${i}`} role="option" aria-selected={i === activeIndex}>
              <a
                href={hit.entry.href}
                className={`hit ${i === activeIndex ? "is-active" : ""}`}
                onMouseMove={() => setActive(i)}
                onClick={(e) => {
                  e.preventDefault();
                  go(hit.entry.href);
                }}
              >
                <span className={`hit-kind kind-${hit.entry.kind}`}>{KIND_LABEL[hit.entry.kind]}</span>
                <span className="hit-main">
                  <span className="hit-title">{hit.entry.title}</span>
                  <span className="hit-context">{hit.entry.context}</span>
                  {hit.entry.body && hit.entry.kind !== "page" ? <span className="hit-body">{hit.entry.body}</span> : null}
                </span>
              </a>
            </li>
          ))}
        </ul>
        {failed ? (
          <p className="search-empty" role="alert">
            The search index did not load, so only the page names show. The site was probably rebuilt.{" "}
            <button type="button" className="btn btn-quiet" onClick={() => window.location.reload()}>
              Reload the page
            </button>
          </p>
        ) : null}
        {query && hits.length === 0 ? <p className="search-empty">Nothing matches. Try one word, like a metric name or a make target.</p> : null}
        {!query ? <p className="search-empty">Type a word: a metric, a topic, a scenario title or a make target.</p> : null}
      </div>
    </dialog>
  );
}
