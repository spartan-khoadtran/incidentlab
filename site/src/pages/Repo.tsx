import type { ReactNode } from "react";
import { Link } from "react-router";
import { PageHead } from "../components/Bits";
import { compose, guide, repo } from "../lib/data";

function Node({ name, note, children, open = false, href }: { name: string; note?: ReactNode; children?: ReactNode; open?: boolean; href?: string }) {
  const label = (
    <>
      <code className="tree-name">{name}</code>
      {note ? <span className="tree-note">{note}</span> : null}
    </>
  );
  if (!children)
    return (
      <li className="tree-leaf">
        {href ? (
          <Link to={href} className="tree-row">
            {label}
          </Link>
        ) : (
          <span className="tree-row">{label}</span>
        )}
      </li>
    );
  return (
    <li className="tree-branch">
      <details open={open}>
        <summary className="tree-row">{label}</summary>
        <ul>{children}</ul>
      </details>
    </li>
  );
}

function layoutNote(path: string): string | undefined {
  return repo.readme.layout.find((l) => l.path === path)?.note;
}

function configEntries(): { path: string; uses: string[] }[] {
  const byPath = new Map<string, string[]>();
  for (const s of compose.services) {
    for (const m of s.configMounts) byPath.set(m.source, [...(byPath.get(m.source) ?? []), `${s.name} reads it at ${m.target}`]);
    if (s.build?.startsWith("config/")) byPath.set(s.build, [...(byPath.get(s.build) ?? []), `build context of ${s.name}`]);
  }
  return [...byPath.entries()].map(([path, uses]) => ({ path, uses })).sort((a, b) => a.path.localeCompare(b.path));
}

export function Repo() {
  const builtBy = new Map<string, string[]>();
  for (const s of compose.services) if (s.build) builtBy.set(s.build, [...(builtBy.get(s.build) ?? []), s.name]);
  const svcImage = builtBy.get("service") ?? [];

  return (
    <div className="page">
      <PageHead kicker="Repo" title="Where things live">
        <p>
          The folders of the repo and the Gradle modules, read from <code>service/settings.gradle</code>, each module's <code>build.gradle</code> header, the README and docker-compose.yml.
        </p>
      </PageHead>
      <div className="tree-wrap">
        <ul className="tree" aria-label="Repository tree">
          <Node name="service/" note={layoutNote("service/")} open>
            {repo.gradle.map((g) => (
              <Node key={g.name} name={`${g.name}`} note={g.note} open={g.name === "deployables"}>
                {g.modules.map((m) => (
                  <Node key={m.path} name={m.dir} note={m.note ?? undefined} />
                ))}
              </Node>
            ))}
            <Node name="Dockerfile" note={`One image for ${svcImage.length} services: ${svcImage.join(", ")}. Each one picks its launcher with SERVICE_LAUNCHER.`} />
          </Node>
          <Node name="config/" note={layoutNote("config/")} open>
            {configEntries().map((c) => (
              <Node key={c.path} name={c.path.replace(/^config\//, "")} note={c.uses.join("; ")} />
            ))}
          </Node>
          <Node name="docs/" note={layoutNote("docs/")} open>
            {guide.map((d) => (
              <Node key={d.file} name={d.file} note={d.note ?? d.title} href={`/guide/${d.slug}`} />
            ))}
          </Node>
          <Node name="site/" note={layoutNote("site/")} />
          <Node name="docker-compose.yml" note={`${compose.services.length} services in ${compose.groups.length} groups, ${compose.volumes.length} named volumes.`} />
          <Node name="Makefile" note={repo.files.makefile ?? undefined} href="/commands" />
          <Node name="README.md" note={repo.files.readme ?? undefined} />
          <Node name="CLAUDE.md" note={repo.files.claude ?? undefined} />
        </ul>
      </div>
      <p className="callout callout-quiet">
        <code>service/tool/labctl/.../scenario/catalog/</code> holds the answers, one file per layer. Do not read it before you play.
      </p>
    </div>
  );
}
