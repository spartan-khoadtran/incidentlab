# Lab API

`labctl daemon` serves this API on port 8090 (`LAB_API_PORT`), next to the control loop. The site's nginx proxies `/api/` to `http://control:8090`, so the browser calls it as `/api/...` on port 4000. Port 8090 is not published to the host.

JSON in and out, snake_case keys. Every response has `Content-Type: application/json`. Every path starts with `/api/`, exactly as written below: nginx and the vite dev server pass the path through unchanged.

Times are epoch seconds (a number). Every duration is a number of seconds, even when the field name has no unit (`p50`, `p99`, `client_mean`). Rates are requests per second.

## Errors

An error is an HTTP status plus `{"error": "<code>", "message": "<one plain sentence a learner can act on>"}`. Some errors add a field, such as `seconds_left`.

| Status | Codes |
|---|---|
| 400 | `bad_json`, `missing_field`, `bad_mode`, `bad_level`, `bad_topic`, `unknown_id`, `no_cli`, `no_match`, `unknown_key`, `bad_multiplier`, `bad_every`, `bad_clients`, `bad_seconds`, `bad_count`, `bad_kind`, `bad_answer` |
| 403 | `forbidden`, `forbidden_host` |
| 404 | `not_found` (no such path), `no_exercise`, `no_session`, `no_question` |
| 405 | `method_not_allowed` |
| 409 | `exercise_open`, `already_answered`, `no_hints_left`, `wrong_mode`, `stale_exercise`, `exercise_changed`, `load_not_one`, `ramp_running` (with `seconds_left`), `unsupported` (with `missing`), `too_early` (with `seconds_left`) |
| 413 | `too_large` (the body limit is 64 KB) |
| 425 | `still_running` (with `seconds_left`) |
| 500 | `internal` |
| 502 | `lab_down`: nginx sends it when the controller does not answer |
| 503 | `prometheus_down` |

`wrong_mode` means the open exercise is a ramp or a bench and the call is for an incident, or the other way round. `stale_exercise` means the exercise was started by an older catalog: clear it, then start a new one. `exercise_changed` means the call sent a `key`, and the open exercise has another key, or none is open: it was cleared, or another one started since the page read it. Nothing changed; read `GET /api/exercise` again. `ramp_running` means a ramp step is running: the ramp holds the load at 1 until its last step ends, `seconds_left` from now.

## The exercise key

Every exercise gets a random `key` when it starts. `n` is not enough: it repeats after a clear. The answer, reveal and clear calls of every mode take an optional `key` in the body. Without it, the call acts on whatever exercise is open. With it, the call acts only when that exercise is still the open one, else 409 `exercise_changed`. The site always sends the key of the exercise it shows. The key is not a spoiler: it is random and says nothing about the scenario.

## Write calls

Every POST must send `Content-Type: application/json` and `X-Lab-Client: web`. Without both: 403 `forbidden`. No CORS header is ever sent, and OPTIONS returns 403, so a page on another origin cannot call the API. A request whose `Host` is not `localhost`, `127.0.0.1`, `[::1]` or `control` gets 403 `forbidden_host`, so a page that rebinds its DNS name to 127.0.0.1 cannot call it either. `labctl api` listens on the loopback address only. A POST with no body counts as `{}`. No GET changes the state.

## No-spoiler rule

Before an exercise is answered or revealed, no response holds its scenario id, title, answer keys or labels, explanation, steps, evidence, cli or guide pointer. `ActiveView` is the only view of an open exercise, and its type has no field for any of them. A quiz answer comes back only after the learner posted an answer to that question.

## Types

```
ActiveView = {
  n: int, key: string, mode: "incident"|"toolbox"|"ramp"|"bench", level: int|null,
  ticket: string,                  // the text the CLI prints at the start, with the ticket time
  started_at: number, elapsed_seconds: number,
  hints_used: int, hints_total: int, hint_penalty: number,   // 0.5
  hints: [string],                 // the hints already taken, in order
  answered: bool,
  rules: string|null               // the toolbox rules in toolbox mode
}
Option = { key: string, label: string, group?: string }      // group only on causes
GuidePointer = { pointer: string, file: string|null }         // file is a name under docs/, like "capacity.md"
Reveal = {
  id, title, level: int, topics: [string],
  answer: { nature: [Option], location: [Option], signal: [Option], cause: Option, partial: [Option] },
  explanation: string, steps: [string], evidence: [string] /* PromQL */, cli: [string],
  guide: [GuidePointer]            // one per "; " part of the doc pointer
}
Result = {
  n: int, score: number, raw: number, max: number, hints_used: int,
  verdicts: { nature: "correct"|"wrong", location: ..., signal: ..., cause: "correct"|"partial"|"wrong" },
  missed: [string], reveal: Reveal
}
HistoryEntry = { n, id, mode, title, level, score: number|null, hints, minutes, at, missed: [string], note }
```

## Endpoints

### Lab

- `GET /api/health` -> `{ ok: true }`
- `GET /api/status` -> `{ services: { api: null|"<error>", pricing, worker, loadgen, noisy, pgobserver }, active: ActiveView|null, load_multiplier: number, base_rps: number, links: { grafana, prometheus, pyroscope } }`. A service is `null` when it answers. `active` is null for a stale exercise too.
- `GET /api/options` -> `{ natures: [Option], locations: [Option], signals: [Option], causes: [Option with group], levels: [int], topics: [string], modes: ["incident", "toolbox"] }`
- `POST /api/load` body `{ multiplier: number }` (0.1 to 6) -> `{ multiplier, rps }`. 400 `bad_multiplier` outside the range. 409 `ramp_running` (with `seconds_left`) while a ramp step runs, because the ramp holds the load at 1 until its last step ends.
- `GET /api/history` -> `{ entries: [HistoryEntry], average: number|null, scored_count: int, by_topic: [{ topic, average, count }], most_missed_cause: { key, label, group, count }|null, ramp_average: number|null, ramp_count: int }`. `average`, `scored_count`, `by_topic` and `most_missed_cause` count scored incident and toolbox entries only. A ramp is scored on other questions, so `ramp_average` and `ramp_count` count scored ramps on their own. `by_topic` lists the weakest topic first.

### Incident and toolbox

- `POST /api/exercises` body `{ mode?: "incident"|"toolbox", level?: int, topic?: string, id?: string, force?: bool }` -> 201 `ActiveView`. 409 `exercise_open` when one is open and `force` is not true. 400 for a bad mode, level, topic or id, or `no_cli` for an id without CLI proof in toolbox mode. 409 `unsupported` (with `missing`, the container settings keys) when the controller cannot apply what the scenario needs, for example with no docker socket; a random pick skips such scenarios and gives this error only when none is left. 409 `load_not_one` when the load multiplier is not 1 and the id is a scenario tuned for the default load; a random pick skips those. Writes the same Grafana annotations as `make new`.
- `GET /api/exercise` -> the open exercise in any mode, so a page reload can rebuild itself: `ActiveView`, plus
  - `plan: { steps: [rps], every, total_seconds }` for a ramp, and `ends_at` (epoch seconds) for a bench, from the start;
  - `reveal: Reveal` once an incident is answered or revealed;
  - `result` once it is answered: the same `Result` that `POST /api/exercise/answer` returned, or the same `RampResult` that `POST /api/ramp/answer` returned. A reveal is not an answer, so a revealed exercise has no `result`. A ramp's `result` is read again from Prometheus and is left out when Prometheus does not answer.

  404 `no_exercise`.
- `POST /api/exercise/hint` -> `ActiveView` with the new hint at the end of `hints`. 409 `no_hints_left` (also for a ramp or a bench), or `already_answered`.
- `POST /api/exercise/answer` body `{ nature, location, signal, cause, note?: string, key?: string }` -> `Result`. Keys come from `/api/options`. 400 `unknown_key` or `missing_field`. 409 `already_answered`, or `exercise_changed`. Keeps the history entry like `make answer`, note included (one line, at most 500 characters). Does not clear the fault.
- `POST /api/exercise/reveal` body `{ key?: string }` -> `Reveal`. Not scored. 409 `exercise_changed`. The first reveal adds a history entry with `score: null`, like `make reveal`. Does not clear the fault.
- `POST /api/exercise/clear` body `{ key?: string }` -> `{ cleared: true }`. Ends the open exercise of any mode, with the annotation "lab: fault cleared". A ramp or a bench that ends before its time also loses its annotations that are still in the future. 409 `exercise_changed` when `key` names another exercise than the open one; with no exercise open, it still answers `{ cleared: true }`.

### Capacity ramp

- `GET /api/ramp/options` -> `{ resources: [Option] }`: the resources the third question offers.
- `POST /api/ramp` body `{ every?: int }` (30 to 300 s, default 60) -> 201 `ActiveView` (mode ramp) plus `plan: { steps: [rps], every, total_seconds }`. 409 `exercise_open`, or `load_not_one` when the load multiplier is not 1.
- `POST /api/ramp/answer` body `{ knee_rps: number, max_rps: number, resource: string, note?: string, key?: string }` -> `RampResult`. `knee_rps` is 0 when there was no knee. 409 `too_early` (with `seconds_left`) before the last step ends, `already_answered`, `exercise_changed`. 400 `unknown_key` for a resource. 503 `prometheus_down`. Keeps the history entry. Does not stop the ramp.
- `POST /api/ramp/reveal` body `{ key?: string }` -> `RampResult` with `score`, `given` and `verdicts` null. 409 `exercise_changed`. The table is empty and `prometheus_down` is true when Prometheus does not answer.

```
RampResult = {
  n, title, score: number|null, max: number, steps_run: int, steps_total: int,
  table: [{ step, target_rps, rps_offered, rps_achieved, p99, error_share, knee: bool }],   // p99 in seconds; null when Prometheus had no sample
  measured: { knee_rps: int|null, knee_step: int|null, max_rps: number|null, resource: string, resource_label: string },
  given: { knee_rps, max_rps, resource }|null, verdicts: { knee: bool, max: bool, resource: bool }|null,
  missed: [string], prometheus_down: bool, explanation: string, guide: [GuidePointer]
}
```

### Closed-loop bench

- `POST /api/bench` body `{ clients?: int, seconds?: int }` (1 to 200 clients, 30 to 600 s) -> 201 `ActiveView` (mode bench) plus `ends_at`. 409 `exercise_open`.
- `GET /api/bench/report` -> `BenchReport` once the bench is over. It changes nothing. 425 `still_running` with `seconds_left`. 503 `prometheus_down`.
- `POST /api/bench/report` body `{ key?: string }` -> the same `BenchReport`, and it closes the bench with a history entry, like `make answer`. 409 `exercise_changed`.

```
Row = { rate_rps: number|null, p50: number|null, p99: number|null, error_share: number|null }   // p50 and p99 in seconds
Window = { client: Row, api: Row, client_mean: number|null, api_in_flight: number|null }   // client_mean in seconds
BenchReport = { n, clients, seconds, before: Window, during: Window, explanation: string, guide: [GuidePointer] }
```

`before` is the 2 minutes before the bench, with the open load. `during` is the bench window without its first 10 s. `client` is what loadgen measured: `rate_rps` is what the clients sent, and `error_share` is the share of requests that failed for them (a timeout, a 5xx or a connection error; a 4xx does not count). `api` is api's own view: `rate_rps` is what api served, and `error_share` is its share of 5xx. `client_mean` is the mean response time the clients saw. `api_in_flight` is the mean number of requests inside api. A null is a number Prometheus had no data for.

### Quiz

- `POST /api/quiz` body `{ count?: int (1 to 300, default 50, the same as `make quiz`), kind?: "numbers"|"concepts"|"all" }` -> 201 `{ session: string, questions: [{ index, kind: "number"|"choice", text, unit?: string, options?: [string] }] }`. The options are already shuffled. No answer is in it.
- `POST /api/quiz/{session}/{index}` body `{ answer: number|string }` -> `{ correct: bool, answer: string, explanation: string, guide: [GuidePointer] }`. A number question takes a number, or a string typed the way the CLI reads it (`"12,5"`). A choice question takes the text of one option. The tolerance is the CLI's. The first answer to a question stands: posting again returns the same grade. 400 `bad_answer`, 404 `no_session` or `no_question`.

Sessions live in the daemon's memory, at most 50, each dropped after 2 hours without an answer. A daemon restart drops them all.

## Try it

From the host, through the site's nginx, with the lab up. The site's nginx answers only when the Host is `localhost` or `127.0.0.1`, and closes the connection for any other name:

```
curl -s localhost:4000/api/status
curl -s -X POST -H 'Content-Type: application/json' -H 'X-Lab-Client: web' localhost:4000/api/exercises -d '{"level": 2}'
curl -s -X POST -H 'Content-Type: application/json' -H 'X-Lab-Client: web' localhost:4000/api/exercise/hint
curl -s localhost:4000/api/options
curl -s -X POST -H 'Content-Type: application/json' -H 'X-Lab-Client: web' localhost:4000/api/exercise/answer \
  -d '{"nature": "<key>", "location": "<key>", "signal": "<key>", "cause": "<key>", "note": "what I read"}'
curl -s -X POST -H 'Content-Type: application/json' -H 'X-Lab-Client: web' localhost:4000/api/exercise/clear
```

Without the lab, for the handbook's dev server: `LAB_STATE=/tmp/lab-state.json ./gradlew :tool:labctl:run --args=api` in `service/` serves the API on 8090 with its own state file and no control loop, so no fault is applied. `npm run dev` in `site/` proxies `/api` to it. `LAB_API=http://localhost:4000 npm run dev` uses the running lab through the site's nginx instead.
