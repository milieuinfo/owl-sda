# Configuration Reference

OWL-SDA is configured through a YAML file. All keys use **kebab-case** and map to camelCase Java properties automatically.

You point OWL-SDA at that file with the required `--config` option:

```bash
java -jar target/owlsda.jar --config examples/project-1/config.yml
```

The value is a plain path, or an explicit `file:` or `classpath:` prefix. See the
[CLI Reference](./cli) for the full option list and prefix semantics.

## Top-Level Properties

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `input-path` | string | — | Path to the input OWL ontology (Turtle or RDF/XML). **Required.** |
| `output-path` | string | — | Path where the generated RDF output is written (Turtle). **Required.** |
| `user-input` | string | — | Natural-language description of the data to generate. **Required.** |
| `user-context` | list | `[]` | Additional context files provided to all agents. See [User Context](#user-context). |
| `program-timeout-ms` | long | `0` | Hard wall-clock timeout for the entire run in milliseconds. `0` disables it. |
| `generation.data-richness` | string | `minimal` | Generation richness profile: `minimal`, `balanced`, or `rich`. Controls how much optional/invented data workers may add while staying SHACL-conformant. |
| `ontology.provide-full-to-workers` | boolean | `true` | Whether workers receive the full ontology in addition to the always-on summary. See [`ontology`](#ontology). |
| `log-level` | string | `INFO` | SLF4J log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. |
| `log-to-file` | boolean | `false` | Write logs to a file in addition to stdout. |
| `log-file-path` | string | `logs/owlsda.log` | Log file path (only used when `log-to-file: true`). |

## `generation`

Controls how aggressively workers enrich generated RDF beyond strict SHACL minimum requirements.

```yaml
generation:
  data-richness: "minimal"
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `data-richness` | string | `minimal` | `minimal`: only required SHACL triples plus minimal coherence data. `balanced`: required triples + modest realistic optional enrichment. `rich`: strong optional enrichment and allows carefully invented predicates when ontology predicates are insufficient. |

## `client`

Controls the LLM models and timeouts for each agent role.

```yaml
client:
  provider: "copilot"
  ollama:
    base-url: "http://localhost:11434"
  openai-compatible:
    base-url: "https://api.openai.com/v1"
    api-key: ""   # leave blank to use the OPENAI_API_KEY environment variable instead
  worker:
    provider: "ollama"
    model: "qwen3:8b"
    timeout-ms: 60000
    between-message-timeout-ms: 0
    batch-size: 5
    pool-count: 1
    context-window-tokens: 0
  supervisor:
    provider: "copilot"
    model: "gpt-5.4"
    timeout-ms: 120000
    between-message-timeout-ms: 0
    context-window-tokens: 0
  reviewer:
    provider: "copilot"
    model: "claude-4.6-sonnet"
    timeout-ms: 60000
    between-message-timeout-ms: 0
    max-review-attempts: 3
    context-window-tokens: 0
```

`client.provider` remains the default provider for all roles. Set `client.worker.provider`,
`client.supervisor.provider`, or `client.reviewer.provider` to override a specific role.
Accepted provider values: `copilot`, `ollama`, `openai-compatible`.

### `client.ollama`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `base-url` | string | `http://localhost:11434` | Base URL for the Ollama API used by roles configured with `provider: ollama`. |
| `think` | boolean | `true` | Whether to allow thinking-capable models (e.g. `qwen3`, `qwen3.5`, `deepseek-r1`) to emit chain-of-thought reasoning before each answer. Set to `false` to request plain answers via Ollama's `think` request field — this can substantially speed up each turn at the cost of some reasoning quality. Ignored by non-thinking models. |

### `client.openai-compatible`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `base-url` | string | `https://api.openai.com/v1` | Base URL for any OpenAI Chat Completions-compatible endpoint. Override to target Azure OpenAI, OpenRouter, or a self-hosted gateway (vLLM, LM Studio, etc.). |
| `api-key` | string | *(none)* | API key sent as `Authorization: Bearer <api-key>`. If left blank or omitted, falls back to the `OPENAI_API_KEY` environment variable. |

Prefer the `OPENAI_API_KEY` environment variable over committing `api-key` directly into a config
file that may be checked into version control. The client fails fast at startup with a clear error
if no key can be resolved from either source, and the key is never written to logs.

### `client.worker`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `provider` | string | *(inherits `client.provider`)* | Optional provider override for worker sessions (`copilot`, `ollama`, or `openai-compatible`). |
| `model` | string | `gpt-5.4` | LLM model identifier for worker agents. |
| `timeout-ms` | int | `60000` | Maximum time to wait for a complete worker response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events within a single response. `0` disables. |
| `batch-size` | int | `5` | Number of SHACL shapes assigned to each worker per round. |
| `pool-count` | int | `1` | Number of parallel worker sessions. |
| `context-window-tokens` | int | `0` | The model's real context window, as configured on the inference server (for example llama.cpp / llama-swap `--ctx-size`). `0` means unset. See [Context windows and compaction](#context-windows-and-compaction). |

### `client.supervisor`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `provider` | string | *(inherits `client.provider`)* | Optional provider override for the supervisor session (`copilot`, `ollama`, or `openai-compatible`). |
| `model` | string | `gpt-5.4` | LLM model identifier for the supervisor agent. |
| `timeout-ms` | int | `120000` | Maximum time to wait for a supervisor response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events. `0` disables. |
| `context-window-tokens` | int | `0` | The supervisor model's real context window. `0` means unset. See [Context windows and compaction](#context-windows-and-compaction). |

### `client.reviewer`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `provider` | string | *(inherits `client.provider`)* | Optional provider override for the reviewer session (`copilot`, `ollama`, or `openai-compatible`). |
| `model` | string | `claude-4.6-sonnet` | LLM model identifier for the reviewer agent. |
| `timeout-ms` | int | `60000` | Maximum time to wait for a reviewer response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events. `0` disables. |
| `max-review-attempts` | int | `3` | Soft review iteration limit. Before this limit, reviewer may return `REVISION_REQUESTED`. On the final attempt, reviewer is instructed to make a terminal decision: `ACCEPTED` or `REJECTED`. |
| `context-window-tokens` | int | `0` | The reviewer model's real context window. `0` means unset. See [Context windows and compaction](#context-windows-and-compaction). |

## `ontology`

Controls how much of the input ontology is injected into each agent session.

```yaml
ontology:
  provide-full-to-workers: true
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `provide-full-to-workers` | boolean | `true` | Whether worker sessions also receive the complete ontology serialized to Turtle. The supervisor and reviewer always receive it. |

A short ontology summary — class and property counts plus a capped sample of names — is added to
**every** session regardless of this setting. `provide-full-to-workers` only decides whether workers
additionally get the full serialization on top of that summary.

Ontologies can run to hundreds of kilobytes, and that cost is paid once per worker session. Set this
to `false` when the summary plus tool-based lookups (`context_reader`, `triplestore_read`) give
workers enough to work with; it frees a large part of the worker context budget and reduces how
often [compaction](#compaction) has to run.

## `shacl`

Controls optional persistence of generated SHACL shapes.

```yaml
shacl:
  output-dir: "target/shacl"
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `output-dir` | string | `""` | Directory where `default-shacl.ttl` and `inferred-shacl.ttl` are cached. Blank (the default) keeps shapes in memory only. |

The value is treated as blank when it is missing, `null`, or empty/whitespace:

- **Blank (default)** — shapes are derived from the ontology in memory on every run and never
  written to disk. Nothing is read back either, so every run reflects the current ontology.
- **Set** — after generation, shapes are written to `<output-dir>/default-shacl.ttl` and
  `<output-dir>/inferred-shacl.ttl`. On a later run, if **both** files already exist they are loaded
  instead of regenerated, which noticeably speeds up repeated runs against the same ontology.

Because a set directory makes runs reuse cached shapes, delete those two files (or point at a fresh
directory) after you change the ontology or the [reasoner](./reasoner) settings.

## `reasoner`

Configures optional OWL/RDFS reasoning applied to the input ontology before shape generation.

See the [Reasoner](./reasoner) page for details.

## `extract`

Controls automatic resolution of external ontologies referenced via `owl:imports` or namespace URIs.

See the [External Ontologies](./external-ontologies) page for details.

## `benchmark`

Enables benchmark snapshots captured after each generation round, plus periodic `LIVE` snapshots while a round is still in progress.

```yaml
benchmark:
  enabled: true
  output-dir: "target/benchmarks"
  live-interval-seconds: 15
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `false` | Whether snapshots are captured at all. Also required by the [Web UI](./web-ui) — it has no data to show without it. |
| `output-dir` | string | `target/benchmarks` | Directory receiving the `live/` and `archive/<timestamp>/` snapshot trees. |
| `live-interval-seconds` | long | `15` | How often a live snapshot is captured while a round or review iteration is still in progress. |

The documented default is `target/benchmarks`, but every shipped example config overrides it to
`examples/project-N/benchmark/`, which is gitignored — so a run of an example writes there, not into
`target/`. Do not confuse that directory with `examples/project-N/benchmark_paper/`, which is a
hand-curated snapshot committed to the repository and is not produced by a run.

See the [Benchmarking](./benchmarking) page for details.

## `tools`

Controls which tools (`SessionHandler` implementations) are available to each agent role, and
configures the two general-purpose tools: `http_call` and the `memory_set`/`memory_get` pair.

```yaml
tools:
  worker:
    disabled: ["http_call"]
  supervisor:
    enabled: ["context_reader", "delegate_tasks", "memory_set", "memory_get"]
  http:
    enabled: true
    allowed-hosts: ["example.org"]
    seed-from-extract-mirrors: true
    seed-from-user-context: true
    allow-post: true
    connect-timeout-ms: 5000
    read-timeout-ms: 15000
    max-response-body-bytes: 1000000
    max-retries: 2
  memory:
    enabled: true
    max-entries: 500
    max-value-bytes: 100000
```

### `tools.worker` / `tools.supervisor` / `tools.reviewer`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | list | *(all tools)* | If set, only the named tools are registered for this role (allowlist). Tool names match each handler's `NAME` constant, e.g. `triplestore_add`, `output_data_writer`, `shacl_validator`, `delegate_tasks`, `http_call`, `memory_set`, `memory_get`. |
| `disabled` | list | `[]` | Tool names to remove for this role (denylist), applied after `enabled`. |

Leaving both lists empty (the default) preserves the existing hardcoded toolset per role —
existing config files keep working unmodified.

### `tools.http`

Adds an `http_call` tool that lets agents make outbound `GET`/`POST` requests, restricted to an
**allowlist of hosts**. By default the allowlist is auto-populated from sources already trusted
elsewhere in the config, so most setups need no extra configuration:

- every host from `extract.mirrors` (including resolved mirror redirects), when
  `seed-from-extract-mirrors` is `true` (default);
- every host from `user-context` entries that use `url`, when `seed-from-user-context` is `true`
  (default);
- any hosts explicitly listed in `allowed-hosts`.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Whether the `http_call` tool is registered at all (subject to per-role `tools.*` filtering). |
| `allowed-hosts` | list | `[]` | Additional hostnames to allow, beyond the auto-seeded ones. |
| `seed-from-extract-mirrors` | boolean | `true` | Auto-allow hosts from `extract.mirrors`. |
| `seed-from-user-context` | boolean | `true` | Auto-allow hosts from `user-context` URL entries. |
| `allow-post` | boolean | `true` | Whether `POST` requests are permitted (in addition to `GET`). |
| `connect-timeout-ms` | int | `5000` | Connection timeout for outbound requests. |
| `read-timeout-ms` | int | `15000` | Read timeout for outbound requests. |
| `max-response-body-bytes` | int | `1000000` | Response bodies are truncated beyond this size. |
| `max-retries` | int | `2` | Retries with exponential backoff on transient failures (connection errors, `5xx`). `4xx` responses are never retried. |

Requests to hosts outside the allowlist, or disallowed methods, return an error result to the
calling agent rather than throwing — the tool call fails gracefully and the agent can react to it.

### `tools.memory`

Adds a `memory_set`/`memory_get` tool pair backed by a simple shared key-value store, scoped to a
single `owlsda` run. Any worker, supervisor, or reviewer session can write a note with `memory_set`
and read it back with `memory_get` — useful for passing short facts or summaries across delegation
rounds without repeating them in every message. The store is **not persisted**: it is cleared when
the run shuts down, and survives `Session.reset()` (which clears conversation history, not this
store).

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Whether `memory_set`/`memory_get` are registered (subject to per-role `tools.*` filtering). |
| `max-entries` | int | `500` | Maximum number of keys the store will hold. |
| `max-value-bytes` | int | `100000` | Maximum size of a single stored value. |

## `compaction`

Automatically summarizes older conversation turns to keep an agent's context size bounded during
long delegation runs, instead of resending the full unbounded history on every request.

```yaml
compaction:
  enabled: true
  token-threshold: 6000
  context-window-threshold-ratio: 0.75
  message-count-threshold: 40
  keep-recent-messages: 8
  ollama-enabled: true
  openai-compatible-enabled: true
  copilot-enabled: false
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Master switch for automatic compaction. |
| `token-threshold` | int | `6000` | **Fallback** trigger, used only when the role's `context-window-tokens` is `0`: compact once the last reported prompt token count reaches this absolute value. `0` disables this trigger. |
| `context-window-threshold-ratio` | double | `0.75` | **Primary** trigger when the role's `context-window-tokens` is set: compact once the last reported prompt token count reaches this fraction of the real context window (`0.75` = 75% used). `0` disables this trigger. |
| `message-count-threshold` | int | `40` | **Fallback** trigger, used only when the role's `context-window-tokens` is `0`: compact once the message history reaches this many entries, regardless of token count. `0` disables this trigger. |
| `keep-recent-messages` | int | `8` | Number of most-recent messages kept verbatim (in addition to the system message); everything older is summarized. |
| `ollama-enabled` | boolean | `true` | Whether compaction applies to `ollama` sessions. |
| `openai-compatible-enabled` | boolean | `true` | Whether compaction applies to `openai-compatible` sessions. |
| `copilot-enabled` | boolean | `false` | Whether compaction applies to `copilot` sessions. Off by default — see below. |

When triggered, OWL-SDA sends a separate summarization request to the same model, replaces the
summarized window with a single synthetic message capturing triples added/removed, outstanding
TODOs, and key decisions, and continues the conversation. If summarization fails for any reason,
the original history is left untouched (compaction fails open — it never blocks a run).

### Context windows and compaction

Which triggers apply depends entirely on whether you configured `context-window-tokens` for that
role. The decision is made per session, in this order:

1. `compaction.enabled` must be `true`, and compaction must be enabled for that session's provider
   (`ollama-enabled`, `openai-compatible-enabled`, or `copilot-enabled`).
2. **If the role's `client.<role>.context-window-tokens` is greater than `0`**, only the
   window-relative trigger is evaluated: compact once the last reported prompt token usage reaches
   `context-window-tokens × context-window-threshold-ratio`. `token-threshold` and
   `message-count-threshold` are **not** consulted at all in this branch.
3. **If `context-window-tokens` is `0`** (the default), the two fallback triggers apply as an OR:
   compact once the last reported prompt token count reaches `token-threshold`, **or** once the
   message history reaches `message-count-threshold` entries.

Prefer setting `context-window-tokens` per role to the window your inference server actually serves.
Once a real window is known it is the authoritative signal, and the raw message count is
deliberately ignored: tool-call-heavy sessions — a worker doing many small
`triplestore_read`/`triplestore_add`/`shacl_validator` round trips — accumulate messages far faster
than they accumulate tokens, and would otherwise compact long before approaching the real limit.
The example configs under `examples/project-1/` set all three roles to `131072`.

### Provider support

Compaction is implemented for the `ollama` and `openai-compatible` providers, and both are enabled
by default. Both keep the conversation history locally, so there is something to summarize and
replace.

`copilot` is disabled by default and enabling it has no useful effect: the Copilot SDK manages its
conversation state opaquely on the server side and exposes only a full `reset()`, not partial
compaction. For `copilot` sessions, that automatic reset between delegation rounds remains the only
context-management lever.

## User Context

You can attach additional text files as context to every agent session:

```yaml
user-context:
  - name: "Example data"
    path: "examples/sample.ttl"
  - name: "LDES guide"
    url: "https://semiceu.github.io/LinkedDataEventStreams/"
  - name: "Domain glossary"
    path: "docs/glossary.txt"
```

Each entry can define either `path` (local file) or `url` (HTTP/HTTPS source). Context content is loaded at startup and added to the system context of all worker, supervisor, and reviewer sessions. Use it to provide domain vocabulary, sample data patterns, web references, or other background knowledge that helps the LLM generate more accurate output.

## Full Example

```yaml
program-timeout-ms: 3600000

input-path:  "ontology/my-ontology.ttl"
output-path: "output/generated.ttl"
user-input:  "Generate 10 example instances covering all major classes."

user-context:
  - name: "Example instances"
    path: "ontology/examples.ttl"

log-level: "INFO"

generation:
  data-richness: "balanced"

ontology:
  provide-full-to-workers: false

client:
  provider: "copilot"
  ollama:
    base-url: "http://localhost:11434"
  openai-compatible:
    base-url: "https://api.openai.com/v1"
    # api-key intentionally omitted here; set OPENAI_API_KEY in the environment instead
  worker:
    provider: "ollama"
    model: "qwen3:8b"
    timeout-ms: 180000
    between-message-timeout-ms: 120000
    batch-size: 3
    pool-count: 3
    context-window-tokens: 32768
  supervisor:
    provider: "openai-compatible"
    model: "gpt-5.4"
    timeout-ms: 300000
    context-window-tokens: 131072
  reviewer:
    provider: "copilot"
    model: "claude-4.6-sonnet"
    timeout-ms: 120000
    max-review-attempts: 4

shacl:
  output-dir: "target/shacl"

reasoner:
  reasoner-type: "owl"
  inferred-output-path: "target/inferred.ttl"

extract:
  cache-dir: "target/cache/external"
  cache-ttl-ms: 86400000

benchmark:
  enabled: true
  output-dir: "target/benchmarks"

tools:
  worker:
    disabled: ["http_call"]
  http:
    allow-post: true
  memory:
    max-entries: 200

compaction:
  enabled: true
  # worker and supervisor declare context-window-tokens, so this ratio drives their compaction;
  # the reviewer runs on copilot here, which is not compacted at all
  context-window-threshold-ratio: 0.75
  token-threshold: 6000
  message-count-threshold: 40
  keep-recent-messages: 8
```
