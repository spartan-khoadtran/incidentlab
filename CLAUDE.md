# Working in this repository

How the lab is built, how to run it, and the design choices the exercises depend on.

## What this is

redlab is a local training lab for incident investigation: comparing layers, Little's Law and queueing, tail latency, retry loops, Kafka lag, trend prediction, traces and false leads. Docker Compose runs a small checkout system (Envoy, api, pricing, Postgres, Redis, Kafka, worker) plus Prometheus, Grafana and Tempo. A controller injects a random fault. The learner investigates with the dashboards, submits an answer and gets a score.

Everything in the repo is English: code, comments, log lines, learner-facing text, Makefile help. The study guide the learners read is `docs/investigation-guide.md`. Scenario and quiz `doc` fields point at its headings as `Section > Subsection (note)`; `GuidePointersTest` checks that every pointer resolves, so a heading rename must update the pointers too.

`service/tool/labctl/src/main/kotlin/com/c0x12c/redlab/labctl/scenario/ScenarioCatalog.kt` holds the answers. The README tells learners not to read it.

## Commands

Everything goes through `make`. `make` with no target lists them.

Lifecycle:
- `make up`: build the shared image inside Docker, start everything, then poll `http://localhost:8080/products/1` until api answers (up to about 4.5 min). Copies `.env.example` to `.env` on first run.
- `make down`: remove containers, networks and volumes. This wipes Postgres data, Prometheus data and exercise history.
- `make nuke`: `down` plus delete the built image.
- `make stop`: stop containers, keep volumes.
- `make up-datadog`: same as `up` with the `datadog` compose profile. Needs `DD_API_KEY` in `.env`.

Exercises (each one runs the `labctl` CLI inside the `control` container):
- `make new` with optional `LEVEL=1|2|3`, `TOPIC=async`, `ID=pool_hold`, `FORCE=1`
- `make hint`, `make answer`, `make reveal`, `make clear`, `make status`, `make list`, `make history`
- `make quiz N=20 KIND=numbers|concepts|all`: theory drills from the guide, numeric (`Numbers`) and multiple choice (`Concepts`). Default KIND is `all`. Does not need the lab running.
- `make load X=1.5`: multiply the background load (default 50 rps).

Development (Gradle lives in `service/`, needs a JDK on the host; Gradle downloads JDK 21 itself):
- `make build` (`./gradlew installDist`), `make test`, `make lint` (ktlint), `make format`.
- One module: `cd service && ./gradlew :tool:labctl:test` or `./gradlew :api:checkout:installDist`.
- One test class: `cd service && ./gradlew :tool:labctl:test --tests '*ExercisesTest*'`.
- `make dashboards` regenerates the Grafana JSON from `service/tool/dashboards` through the built image. Grafana reads the bind-mounted folder, so no restart is needed. Do not edit the JSON by hand.
- `docker compose up -d --build api` rebuilds after a code change. All services share one image (`redlab-svc:latest`); other services keep the old code until you restart them too.
- `make logs S=api` (empty `S` means all services), `make ps`.

Host ports: Envoy 8080 (admin 9901), Grafana 3000, Prometheus 9090. Tempo has no host port: traces are read in Grafana. Service ports (api 8000, pricing 8001, worker 8002, loadgen 8003, pgobserver 8004, noisy 8005) are only reachable inside the compose network.

## Architecture

### Request path

loadgen -> envoy:8080 -> api:8000 -> Redis (product cache), Postgres (pool), pricing:8001 (`/price`, `/recommend`), Kafka topic `orders` -> worker (consumer group `order-worker`, 3 partitions) -> Postgres.

loadgen is an open-model Poisson generator per tenant. It makes the root sampling decision for traces (`LOADGEN_TRACE_SAMPLE_RATIO`, default 0.25). Every other service samples with `parentbased_traceidratio` at 1.0, so they follow loadgen.

### Gradle layout (mirrors the service-opstracer conventions)

- `core/`: framework-free libraries. `module-logging` (the `Logging` interface, `info { }` extensions, `logback-base.xml`), `module-utility` (`ResizableGate`, jitter, CPU burner, `retryForever`), `module-faults` (the `Faults` registry), `module-metrics` (Micrometer wrappers with Prometheus names, process and cgroup binders), `module-tracing` (OpenTelemetry span and propagation helpers), `module-database` (Hikari pool, JDBC helpers).
- `shared/`: `module-exception` (`ClientException`, `ErrorResponse`, `CheckoutError`), `module-client` (DTOs and the Retrofit `PricingApi`), `module-infra` (the Micronaut glue every service inherits: `/admin/faults`, `/metrics`, the RED filter, the event loop probe, `AppDatabaseConfig` and `DatabaseFactory`, `AppKafkaConfig`, the error handler).
- `domain/module-checkout/`: `module-api` (interfaces), `module-repository` (the SQL, one `/* q=name */` statement each, timed per call), `module-impl` (the manager, DB sessions, pricing client, cache, Kafka producer, backfill job, admission filter, `CheckoutFactory`).
- Deployables are thin composition roots with an `object Main` and an `application.yml`: `api/checkout`, `api/pricing`, `worker/orders` (the Kafka consumer), `loadgen`, `noisy`, `pgobserver` (flat: a load generator, a batch job and an exporter are neither an api nor a worker), `tool/labctl` (plain Kotlin, no Micronaut), `tool/dashboards`.
- Conventions: `@Factory` classes build the plain classes; the classes themselves are never `@Singleton` unless they are loops or filters. Typed `@ConfigurationProperties` data classes bind `app.*`. Package root is `com.c0x12c.redlab`; `core/module-logging` keeps `com.c0x12c.logging`. JDK 21 toolchain (the BLOCKING executor runs on virtual threads). ktlint with the copied `.editorconfig`.

### One image, many launchers

`service/Dockerfile` is a two-stage build: Gradle `installDist` for every deployable, then a JRE image with every distribution under `/run/app/<name>/`. `docker-compose.yml` picks the launcher per service with `SERVICE_LAUNCHER`. A new module needs no Dockerfile change, but it does need a rebuild.

### What every Micronaut service gets from shared/module-infra

- `GET /metrics` (`PrometheusMeterRegistry` built by hand so no auto binder renames a series). It answers in OpenMetrics when the `Accept` header asks for it, because only OpenMetrics carries exemplars. `CurrentSpanContext` gives the registry the current OTel span, so a histogram recorded inside a sampled span gets a `trace_id` exemplar.
- `GET/PUT /admin/faults`, `POST /admin/reset`.
- `RedMetricsFilter` (just after the TRACING phase, so it runs inside the server span; blocking executor): `http_server_requests_total{route,method,status}`, `http_server_duration_seconds{route,outcome}`, `http_server_inflight`. Paths under `/metrics`, `/admin` and `/health` are skipped.
- `EventLoopRuntime`: `event_loop_lag_seconds` and `onEventLoop { }` to park CPU work on the single Netty event loop thread (`micronaut.netty.event-loops.default.num-threads: 1` in api and pricing).
- `ProcessMetrics` (`process_cpu_seconds_total`, `process_resident_memory_bytes`, `process_start_time_seconds`) and `CgroupMetrics` (`cgroup_*`, read from `/sys/fs/cgroup`).
- Tracing comes from `micronaut-tracing-opentelemetry-http` configured with `otel.*` keys in each `application.yml`; the OTLP exporter uses the JDK sender, not OkHttp. `otel.traces.exporter: otlp` must stay set: Micronaut's factory defaults the exporter to none, and then every span is silently dropped.

`Faults` is a map of defaults plus overrides. Each deployable publishes one `Faults` bean with its own defaults (`CheckoutFaults`, `PricingFaults`, `OrdersFaults`, `LoadgenFaults`, `NoisyFaults`). A key only does something when the service reads it.

### Fault control loop (how an exercise changes the system)

`control` runs `labctl daemon`. Every 3 seconds it:
1. Reads `/state/state.json` (volume `labstate`). Shape: `{active, history, base}`.
2. Builds the override dict per service: `base` (from `make load`) plus `ScenarioCatalog.byId[active.id].faults(params)` (`DesiredState.of`).
3. PUTs that dict to each service's `/admin/faults`. A service not named by the scenario gets `{}`, which resets it.
4. Sets the api container's CPU limit through the Docker socket (`DockerControl`, raw engine API over `docker-java-transport-zerodep`). Base is 2.0 cores; a scenario's `docker` map overrides it.

The daemon owns fault state. A manual PUT to `/admin/faults` is reverted within 3 seconds. To change behavior, change `state.json` through the `make` commands, or change the scenario.

### Scenario contract (`ScenarioCatalog.kt`)

Each `Scenario` has: `id`, `title`, `level` (1 to 3), `topics`; `params(random, context)` (context carries `cpuCount`, `productDetailSeconds`, the measured cost of one uncached product query, used to calibrate load, and `tenants`, loadgen's default rate per tenant); `faults(params) -> FaultSpec(services, docker)` runs every daemon tick and `Params.elapsedSeconds` is the time since the start, so a fault can change mid-exercise (`lag_after_fix` rolls the worker back after `fix_after`); `ticket(params)`, `hints` (3), `explain(params)`; `steps` (the investigation trace printed after the explanation); `answer` (`nature`, `location` and `signal` are sets of accepted keys from `Answers`, `cause` one key, `partial` the causes worth 1 point: a visible consequence one step before the root cause); `evidence` (PromQL), `doc`; optional `marker(params)` (real Grafana annotation 0 to 3 minutes before start) and `decoy` (probability of a fake annotation between 10 minutes before and 1 minute after start). `Answers.CAUSES` also holds `DISTRACTORS`, causes no scenario has, and some causes belong to two scenarios (`pool_exhausted`, `single_thread_cpu`, `client_timeout`), so the cause list is not a copy of the catalog.

Scoring: nature 1, location 1, signal 1, cause 2 (partial 1), minus 0.5 per hint; 5 points max. After scoring, `make answer` asks for a one-line note, kept in history with the missed questions. `make new` skips the last 5 played ids. `ScenarioCatalogTest` renders every scenario with a seeded random and checks the fault targets, so run it after any catalog change.

### Metric conventions

- Declare Micrometer names without the `_total` the Prometheus registry appends; `LabHistogram` is a Micrometer `Timer` with the shared `LatencyBuckets`, and its names already end in `_seconds`. Do not use a `DistributionSummary` for latency: Micrometer rounds a summary value up to a whole number before it picks the bucket, so every sub-second value lands in the 1.0 bucket (`LabHistogramTest` guards this). Use `LabCounter`, `LabHistogram`, `LabGauge` for label-keyed families.
- `http_client_*`: api calling pricing (`target`, `route`). `db_client_session_seconds{op}`: pool wait plus hold. `db_client_duration_seconds{query}`: the statement only. `db_pool_*`, `app_admission_*`: software resources inside api.
- `pg_*` come from pgobserver, which names queries from the `/* q=name */` comment at the start of each SQL string in `module-repository`. A new statement must carry that comment or it will not show up.
- `kafka_*`: produce (api) and consume, lag, retries (worker). `client_*`: loadgen. Prometheus job names equal compose service names.

### Design choices the exercises depend on

Changing one of these breaks the scenario named in brackets:
- `AdmissionFilter` (FIRST phase) runs before `RedMetricsFilter` (just after TRACING), so queue time shows at Envoy, not at api. [`queue_before_app`]
- A latency histogram gets exemplars only if it is recorded while its span is current. The RED filter, the worker's `kafka.consume` span and loadgen's root span all record inside their span. Record a new histogram the same way or its panel shows no dots.
- `ApiGates.pool` is a `ResizableGate` in front of the real Hikari pool (max 24). `pool_max` default is 4. [`pool_hold`, `db_lock`]
- A healthy checkout holds its connection about 50 ms: `price_work_ms` (default 45) is app work after loading prices, outside any transaction, so Postgres stays idle. At 15 checkouts/s that is about 0.8 connections, so a pool of 1 saturates and a 4x surge fills the pool of 4. [`pool_shrunk`, `global_surge`, `pool_hold`]
- CPU burn runs through `EventLoopRuntime.onEventLoop` on the single Netty thread. api and pricing both have `cpus: "2.0"`, so one saturated thread shows as half the limit with no throttling. [`single_thread_cpu`, `pricing_cpu`]
- loadgen multiplies `rate_multiplier` (what `make load` sets) with `surge_multiplier` (what a scenario sets), so a surge exercise keeps the learner's load setting. [`global_surge`]
- `MemoryLeak` allocates off heap with `Unsafe`, so the kernel OOM killer ends the process, not a Java `OutOfMemoryError`. api has `mem_limit: 512m` and `memswap_limit: 512m`. Without the swap cap, Docker allows twice the limit as swap, and the api swaps and slows down instead of being killed. [`oom`]
- Envoy has `healthy_panic_threshold: 0`, so it returns 503 itself when api is unhealthy. A 503 from `/health` is an immediate failure for Envoy (`unhealthy_threshold` does not apply), so the share of 503s is about `health_fail_prob`. [`lb_no_healthy`, `lb_flapping`]
- The controller sets the api CPU limit from a base of 2.0 cores; the throttle scenario draws 0.07 to 0.1 cores, because api averages about 0.06 cores at 50 rps. [`cpu_throttle`]
- `noisy` has `cpu_shares: 65536`. [`noisy_neighbor`]
- No index on `reviews.product_id`; the Redis cache keeps `product_detail` cheap. The cache key includes the TTL, so a config change starts with an empty cache. [`cache_miss`, `tenant_spike`]
- The worker processes each partition in order and retries a failed message for ever. [`poison_message`, `consumer_backlog`]

### Data and state

- `config/postgres/init.sql` runs only when the `pgdata` volume is created. Schema changes need `make down` first.
- Exercise state and history live in `state.json` on the `labstate` volume. `make down` deletes it.
- Grafana runs as anonymous Admin. Datasources are provisioned (Prometheus uid `prometheus`, Tempo uid `tempo`). Prometheus exemplars link to Tempo by `trace_id`. Dashboards come from `config/grafana/dashboards/`, generated by `tool/dashboards`.
