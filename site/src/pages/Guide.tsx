import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Navigate, NavLink, useLocation, useParams } from "react-router";
import { docBySlug, guide, type GuideDoc } from "../lib/data";

const loaders = import.meta.glob<{ default: { slug: string; html: string } }>("../generated/docs/*.json");

function loaderFor(slug: string) {
  return loaders[`../generated/docs/${slug}.json`];
}

function groups(): { title: string; docs: GuideDoc[] }[] {
  const out: { title: string; docs: GuideDoc[] }[] = [];
  for (const d of guide) {
    const g = out.find((x) => x.title === d.group);
    if (g) g.docs.push(d);
    else out.push({ title: d.group, docs: [d] });
  }
  return out;
}

function useActiveHeading(ids: string[], ready: boolean): string | null {
  const [active, setActive] = useState<string | null>(null);
  useEffect(() => {
    if (!ready) return;
    const els = ids.map((id) => document.getElementById(id)).filter((e): e is HTMLElement => Boolean(e));
    const seen = new Map<string, boolean>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const e of entries) seen.set(e.target.id, e.isIntersecting);
        const firstVisible = els.find((el) => seen.get(el.id));
        if (firstVisible) setActive(firstVisible.id);
        else {
          const above = els.filter((el) => el.getBoundingClientRect().top < 120);
          if (above.length) setActive(above[above.length - 1].id);
        }
      },
      { rootMargin: "-80px 0px -55% 0px" },
    );
    els.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [ids, ready]);
  return active;
}

function GuideNav({ current }: { current: GuideDoc }) {
  return (
    <nav className="guide-nav" aria-label="Guide files">
      {groups().map((g) => (
        <div key={g.title} className="guide-nav-group">
          <p className="guide-nav-title">{g.title}</p>
          <ul>
            {g.docs.map((d) => (
              <li key={d.slug}>
                <NavLink to={`/guide/${d.slug}`} className="guide-nav-file">
                  <span>{d.title.split(":")[0]}</span>
                  <code>{d.file}</code>
                </NavLink>
                {d.slug === current.slug ? (
                  <ul className="guide-nav-sections">
                    {d.headings
                      .filter((h) => h.depth === 2)
                      .map((h) => (
                        <li key={h.id}>
                          <a href={`#${h.id}`}>{h.text}</a>
                        </li>
                      ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}

function Toc({ doc, active }: { doc: GuideDoc; active: string | null }) {
  const sections = doc.headings.filter((h) => h.depth === 2);
  return (
    <nav className="toc" aria-label="On this page">
      <p className="toc-title">On this page</p>
      <ol>
        {sections.map((s) => {
          const subs = doc.headings.filter((h) => h.depth === 3 && h.section === s.raw);
          return (
            <li key={s.id}>
              <a href={`#${s.id}`} className={active === s.id ? "is-active" : ""} aria-current={active === s.id ? "location" : undefined}>
                {s.text}
              </a>
              {subs.length ? (
                <ol>
                  {subs.map((h) => (
                    <li key={h.id}>
                      <a href={`#${h.id}`} className={active === h.id ? "is-active" : ""} aria-current={active === h.id ? "location" : undefined}>
                        {h.text}
                      </a>
                    </li>
                  ))}
                </ol>
              ) : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function Guide() {
  const { slug } = useParams();
  const { hash } = useLocation();
  const doc = slug ? docBySlug(slug) : undefined;
  const [html, setHtml] = useState<{ slug: string; html: string } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const article = useRef<HTMLDivElement>(null);
  const ids = useMemo(() => doc?.headings.filter((h) => h.depth <= 3).map((h) => h.id) ?? [], [doc]);
  const ready = html?.slug === doc?.slug;
  const active = useActiveHeading(ids, ready);

  useEffect(() => {
    if (!doc) return;
    let live = true;
    const load = loaderFor(doc.slug);
    if (load)
      load().then(
        (m) => live && setHtml(m.default),
        () => live && setFailed(doc.slug),
      );
    return () => {
      live = false;
    };
  }, [doc]);

  useEffect(() => {
    if (ready && hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView({ block: "start" });
  }, [ready, hash]);

  useEffect(() => {
    if (doc) document.title = `${doc.title} · incidentlab handbook`;
    return () => {
      document.title = "incidentlab handbook";
    };
  }, [doc]);

  if (!slug) return <Navigate to={`/guide/${guide[0].slug}`} replace />;
  if (!doc)
    return (
      <div className="page">
        <h1>No such guide file</h1>
        <p>
          <Link to="/guide">Back to the guide</Link>
        </p>
      </div>
    );

  const index = guide.indexOf(doc);
  const prev = guide[index - 1];
  const next = guide[index + 1];
  const minutes = Math.max(1, Math.round(doc.words / 220));
  const sectionCount = doc.headings.filter((h) => h.depth === 2).length;

  return (
    <div className="guide">
      <aside className="guide-side">
        <GuideNav current={doc} />
      </aside>
      <article className="guide-main" aria-labelledby="doc-title">
        <details className="drawer guide-drawer">
          <summary>All guide files</summary>
          <GuideNav current={doc} />
        </details>
        <header className="doc-head">
          <p className="kicker">
            {doc.group} · <code>docs/{doc.file}</code>
          </p>
          <h1 id="doc-title">{doc.title}</h1>
          <p className="doc-meta">
            {sectionCount} sections · about {minutes} min of reading{doc.note ? ` · ${doc.note}` : ""}
          </p>
        </header>
        <details className="drawer toc-drawer">
          <summary>On this page</summary>
          <Toc doc={doc} active={active} />
        </details>
        {ready && html ? (
          <div ref={article} className="prose html-content" dangerouslySetInnerHTML={{ __html: html.html }} />
        ) : failed === doc.slug ? (
          <div className="loading" role="alert">
            <p>{doc.file} did not load. The site was probably rebuilt since you opened it.</p>
            <button type="button" className="btn" onClick={() => window.location.reload()}>
              Reload the page
            </button>
          </div>
        ) : (
          <p className="loading" role="status">
            Loading {doc.file}...
          </p>
        )}
        <nav className="doc-pager" aria-label="Next and previous files">
          {prev ? (
            <Link to={`/guide/${prev.slug}`} className="pager-prev">
              <span>Previous</span>
              {prev.title}
            </Link>
          ) : (
            <span />
          )}
          {next ? (
            <Link to={`/guide/${next.slug}`} className="pager-next">
              <span>Next</span>
              {next.title}
            </Link>
          ) : null}
        </nav>
      </article>
      <aside className="guide-toc">
        <Toc doc={doc} active={active} />
      </aside>
    </div>
  );
}
