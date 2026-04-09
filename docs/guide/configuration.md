# Configuration Reference

OWL-SDA is configured through a YAML file. All keys use **kebab-case** and map to camelCase Java properties automatically.

## Top-Level Properties

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `input-path` | string | — | Path to the input OWL ontology (Turtle or RDF/XML). **Required.** |
| `output-path` | string | — | Path where the generated RDF output is written (Turtle). **Required.** |
| `user-input` | string | — | Natural-language description of the data to generate. **Required.** |
| `user-context` | list | `[]` | Additional context files provided to all agents. See [User Context](#user-context). |
| `program-timeout-ms` | long | `0` | Hard wall-clock timeout for the entire run in milliseconds. `0` disables it. |
| `generation.data-richness` | string | `minimal` | Generation richness profile: `minimal`, `balanced`, or `rich`. Controls how much optional/invented data workers may add while staying SHACL-conformant. |
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
  worker:
    model: "gpt-5.1"
    timeout-ms: 60000
    between-message-timeout-ms: 0
    batch-size: 5
    pool-count: 1
  supervisor:
    model: "gpt-5.1"
    timeout-ms: 120000
    between-message-timeout-ms: 0
  reviewer:
    model: "gpt-5.1"
    timeout-ms: 60000
    between-message-timeout-ms: 0
    max-review-attempts: 3
```

### `client.worker`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `model` | string | `gpt-5.1` | LLM model identifier for worker agents. |
| `timeout-ms` | int | `60000` | Maximum time to wait for a complete worker response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events within a single response. `0` disables. |
| `batch-size` | int | `5` | Number of SHACL shapes assigned to each worker per round. |
| `pool-count` | int | `1` | Number of parallel worker sessions. |

### `client.supervisor`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `model` | string | `gpt-5.1` | LLM model identifier for the supervisor agent. |
| `timeout-ms` | int | `120000` | Maximum time to wait for a supervisor response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events. `0` disables. |

### `client.reviewer`

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `model` | string | `gpt-5.1` | LLM model identifier for the reviewer agent. |
| `timeout-ms` | int | `60000` | Maximum time to wait for a reviewer response. |
| `between-message-timeout-ms` | int | `0` | Maximum idle time between assistant events. `0` disables. |
| `max-review-attempts` | int | `3` | Soft review iteration limit. Before this limit, reviewer may return `REVISION_REQUESTED`. On the final attempt, reviewer is instructed to make a terminal decision: `ACCEPTED` or `REJECTED`. |

## `shacl`

Controls optional persistence of generated SHACL shapes.

```yaml
shacl:
  output-dir: "target/shacl"
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `output-dir` | string | `""` | Directory where `default-shacl.ttl` and `inferred-shacl.ttl` are saved/loaded. If empty, shapes are generated in-memory every run. |

When `output-dir` is set and both files already exist, they are loaded instead of regenerated, significantly speeding up repeated runs.

## `reasoner`

Configures optional OWL/RDFS reasoning applied to the input ontology before shape generation.

See the [Reasoner](./reasoner) page for details.

## `extract`

Controls automatic resolution of external ontologies referenced via `owl:imports` or namespace URIs.

See the [External Ontologies](./external-ontologies) page for details.

## `benchmark`

Enables benchmark snapshots captured after each generation round.

See the [Benchmarking](./benchmarking) page for details.

## User Context

You can attach additional text files as context to every agent session:

```yaml
user-context:
  - name: "Example data"
    path: "examples/sample.ttl"
  - name: "Domain glossary"
    path: "docs/glossary.txt"
```

These files are loaded at startup and added to the system context of all worker, supervisor, and reviewer sessions. Use them to provide domain vocabulary, sample data patterns, or any other background knowledge that helps the LLM generate more accurate output.

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

client:
  worker:
    model: "gpt-5.1-mini"
    timeout-ms: 180000
    between-message-timeout-ms: 120000
    batch-size: 3
    pool-count: 3
  supervisor:
    model: "gpt-5.1"
    timeout-ms: 300000
  reviewer:
    model: "gpt-5.1"
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
```
