# incidentlab: practice incident investigation

A lab for the part of on-call nobody teaches: you get a ticket, a few dashboards, some traces, and a
system that is slow or failing for a reason you do not know yet. incidentlab runs a small checkout system with
Docker Compose (Envoy, api, pricing, Postgres, Redis, Kafka, worker) plus Prometheus, Grafana and Tempo.
It injects a random fault and opens a ticket. You find the cause, and the lab scores four answers: the
kind of problem, the layer, the signal that proves it and the root cause.

You compare every layer from the client down to the host. Along the way the exercises train:

- Little's Law and queueing, to explain why latency rises long before a resource is at 100%
- percentiles and tail latency, where p99 jumps and the mean stays flat
- loops that feed themselves: slow calls make timeouts, timeouts make retries, retries make load
- Kafka lag, and how long a backlog takes to drain after the fix
- a trend that predicts an outage before users see it, like a slow memory leak
- traces and exemplars, to go from a slow panel to the slow span
- false leads: a deploy note that is not the cause, a busy host that hurts no one

The method is in [docs/investigation-guide.md](docs/investigation-guide.md): RED and USE in detail, how
the numbers differ between layers, how to link them with Little's Law, the other methods and anti-methods
around them, and where each layer lives in the dashboards. Every exercise and every quiz question points
back at a section of it.

> **For training only.** This repo exists to teach incident investigation.
> The services break on purpose, and the code is not meant for production use.

## Two ways to practice

| | `make new` | `make quiz` |
|---|---|---|
| What it is | An incident on the running lab | Theory drills in the terminal |
| What you do | Read a ticket, investigate in Grafana, then `make answer` | Answer questions one by one |
| Needs the lab running | Yes (`make up` first) | No |
| Time | 10 to 30 minutes per exercise | About 1 minute per question |
| What it trains | The whole investigation: find the layer, the signal and the cause | The ideas and the math behind it: Little's Law, queueing, tail latency, what each metric means |
| How many | 32 scenarios, random numbers every run | 32 number drills with new numbers every run, and 81 multiple choice questions |
| Score | 0 to 5 per exercise, kept in `make history` | Right answers out of the questions asked, not saved |

A good order: run `make quiz` until the guide makes sense, then `make new` to use it on a live system.
Both point back at the section of the guide to read again.

`make quiz` asks 50 questions by default, half numbers and half concepts. Change it with `N` and `KIND`:

```bash
make quiz                  # 50 mixed questions
make quiz N=10             # 10 mixed questions
make quiz KIND=concepts    # multiple choice only
make quiz KIND=numbers     # calculations only
```

### Toolbox mode

`make toolbox` is `make new` without Grafana. You get the same ticket and the same four questions, but you investigate only from a shell: `make shell` opens a root shell on the Docker VM with `vmstat`, `pidstat`, the cgroup files, `psql`, `bpftrace` and `perf`. It takes the same `LEVEL`, `TOPIC`, `ID` and `FORCE` options, and `make list` tags the scenarios it can play with `[toolbox]`. The commands and what to read in them are in [docs/toolbox.md](docs/toolbox.md). `bpftrace` and `perf` need the VM kernel's support: they work on OrbStack and may fail on Docker Desktop (`kernel-check` in the shell tells you).

### Ramp and bench

- `make ramp`: the load rises in steps from 50 to 300 rps while something may limit the lab. You find the knee, the highest throughput and the resource that filled first. Scored from what Prometheus recorded. See [docs/capacity.md](docs/capacity.md).
- `make bench`: closed-loop clients for 2 minutes, then a report of the minutes before against the bench. Not scored. It shows why a simple benchmark reports a better p99 than users get.

## How it works

### The system

![incidentlab architecture](docs/diagrams/architecture.png)

- **Request path.** `loadgen` sends about 50 rps of checkout traffic through Envoy to `api`. `api` reads
  products from Redis and Postgres, calls `pricing`, then writes each order to Kafka. `worker` consumes
  the `orders` topic and marks each order done in Postgres.
- **Fault loop.** `control` runs `labctl daemon`. Every 3 seconds it reads the active exercise from
  `state.json`, sends the fault settings to each service on `PUT /admin/faults`, and sets the api CPU
  limit. A manual change is reverted on the next tick.
- **What you look at.** Prometheus scrapes `/metrics` on every service. Every service sends spans to
  Tempo. Grafana has the RED and USE dashboards by layer, and the traces too. Each dot on a latency
  panel is an exemplar: click it to open one real request that landed in that bucket. api, pricing and
  worker also run a profiling agent: Explore > Pyroscope shows which code uses their CPU.

### One exercise

![How an exercise runs](docs/diagrams/exercise-flow.png)

1. `make up` builds and starts everything. Wait 3 to 5 minutes so the dashboards show a normal baseline.
2. `make new` picks a scenario you did not play in the last 5 rounds. It draws random parameters and
   saves them as the active exercise. Within 3 seconds the daemon turns the fault on.
3. You read the ticket and investigate in Grafana. Traces are there too (Explore > Tempo).
4. `make answer` asks four multiple choice questions: the nature of the problem, the location, the
   signal that proves it, and the cause. You get nature 1, location 1, signal 1, cause 2 (1 for a
   partial cause), so 5 points max. Each `make hint` you used takes 0.5 off. Then you can type one
   line about the evidence you used; `make history` shows it next to the score, with your average
   by topic and the cause you miss most.
5. You see the explanation, the PromQL that proves it, and the section of the guide to read again.
   Press Enter to clear the fault, then `make new` again.

Hints, answers, scoring and the `make quiz` drills are all built into `labctl`. They run offline and
need no account or API key. After `make answer`, the trace shows the investigation step by step, from
the ticket to the cause.

For an interactive view (zoom, search, trace a path), open `docs/diagrams/architecture.html` or
`docs/diagrams/exercise-flow.html` in a browser. The `.json` files next to them are the diagram sources.

## Quick start

You need Docker Desktop (give it 4 CPUs and 8 GB RAM, because 15 containers run at once and the CPU
exercises need cores to spare) or OrbStack (no settings needed), and `make`.
Nothing else is installed on your machine: the services are built inside Docker.

```bash
make up        # build + start, wait until ready (the first build takes several minutes)
# open http://localhost:3000 (Grafana: dashboards and traces)
make new       # an incident on the live lab
make hint      # a hint (costs 0.5 point)
make answer    # submit, see the explanation and the step-by-step trace
make quiz      # theory drills, no lab needed (50 questions)
make down      # stop and remove everything
```

`make` with no target lists every command.

### The handbook

`make up` also starts a small website at http://localhost:4000 (`make site` opens it). It has the guide with a search, the system diagram, every command, and the catalog by level and topic. It shows scenario titles only, never an answer. It is built from this repo, so it always matches the docs and the Makefile.

## Layout

- `service/`: the Kotlin services, one Gradle build. `tool/labctl` is the controller and the CLI;
  `tool/labctl/.../scenario/catalog/` holds the answers, do not read it before you play.
- `config/`: Envoy, Prometheus, Grafana (pre-built dashboards), Postgres.
- `service/tool/dashboards`: regenerates the Grafana dashboards (`make dashboards`).
- `docs/`: the study guide. Every exercise and quiz question points at one of its sections.
- `site/`: the handbook at http://localhost:4000, a React site built from the docs, the Makefile and docker-compose.yml.

## Development

The services are Kotlin on Micronaut, built with Gradle. `make up` builds them inside Docker, so a JDK is
optional. With a JDK on the machine (Gradle downloads JDK 21 itself):

```bash
make build     # ./gradlew installDist
make test      # unit tests
make lint      # ktlint
```

To rebuild one service after a change: `docker compose up -d --build api`. Every service shares the
`incidentlab-svc` image, so this rebuilds the image once; restart the other services to pick it up.

## Datadog (optional, experimental)

Put `DD_API_KEY` in `.env`, then `make up-datadog`. Traces go to the Datadog Agent instead of Tempo.

## License

MIT. See [LICENSE](LICENSE).
