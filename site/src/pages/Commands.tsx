import { PageHead, Rich } from "../components/Bits";
import { Cmd } from "../components/Copy";
import { make } from "../lib/data";

function title(text: string): string {
  return text[0].toUpperCase() + text.slice(1);
}

function Help({ text }: { text: string }) {
  return (
    <p className="target-help">
      <Rich text={text} />
    </p>
  );
}

export function Commands() {
  return (
    <div className="page">
      <PageHead kicker="Commands" title="Every make target">
        <p>
          Read from the Makefile, grouped the way it groups them. <code>make</code> with no target prints the same list.
        </p>
      </PageHead>
      <nav className="jump" aria-label="Command groups">
        {make.groups.map((g) => (
          <a key={g.id} href={`#group-${g.id}`}>
            {title(g.title)}
            <span>{g.targets.length}</span>
          </a>
        ))}
      </nav>
      {make.groups.map((g) => (
        <section key={g.id} className="block" aria-labelledby={`group-${g.id}`}>
          <div className="block-head">
            <h2 id={`group-${g.id}`}>{title(g.title)}</h2>
            {g.note ? <p>{title(g.note)}.</p> : null}
          </div>
          <div className="targets">
            {g.targets.map((t) => {
              const example = t.options.filter((o) => o.example && !o.example.includes("|")).slice(0, 1);
              return (
                <article key={t.name} id={`make-${t.name}`} className="target">
                  <Cmd text={`make ${t.name}`} />
                  <Help text={t.help} />
                  {t.options.length ? (
                    <ul className="target-options" aria-label="Options">
                      {t.options.map((o) => (
                        <li key={o.name}>
                          <code>{o.example ? `${o.name}=${o.example}` : o.name}</code>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  {example.length ? <Cmd text={`make ${t.name} ${example.map((o) => `${o.name}=${o.example}`).join(" ")}`} note="example" /> : null}
                </article>
              );
            })}
          </div>
        </section>
      ))}
      <section className="block">
        <div className="block-head">
          <h2>When the lab is up</h2>
          <p>
            What <code>make up</code> prints at the end.
          </p>
        </div>
        <dl className="links-table">
          {make.links.map((l) => (
            <div key={l.label}>
              <dt>{l.label}</dt>
              <dd>{l.value}</dd>
            </div>
          ))}
        </dl>
      </section>
    </div>
  );
}
