import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(process.env.LAB_ROOT ?? join(SITE, ".."));
const COMMITTED = join(SITE, "src/data/lab.json");
const write = process.argv.includes("--write");

const dir = mkdtempSync(join(tmpdir(), "lab-export-"));
const out = write ? COMMITTED : join(dir, "lab.json");
try {
  execFileSync("./gradlew", ["-q", "--console=plain", ":tool:labctl:run", `--args=export ${out}`], { cwd: join(ROOT, "service"), stdio: ["ignore", "inherit", "inherit"] });
  if (write) {
    console.log(`check:data: wrote ${COMMITTED}`);
    process.exit(0);
  }
  const fresh = readFileSync(out, "utf8");
  const committed = readFileSync(COMMITTED, "utf8");
  if (fresh !== committed) {
    const a = committed.split("\n");
    const b = fresh.split("\n");
    const first = a.findIndex((line, i) => line !== b[i]);
    console.error(`check:data: site/src/data/lab.json is stale (first difference at line ${first + 1}).`);
    console.error(`  committed: ${a[first] ?? "(end of file)"}`);
    console.error(`  fresh:     ${b[first] ?? "(end of file)"}`);
    console.error("Run `npm run export:lab` in site/ and commit the result.");
    process.exit(1);
  }
  console.log("check:data: site/src/data/lab.json matches a fresh labctl export");
} finally {
  rmSync(dir, { recursive: true, force: true });
}
