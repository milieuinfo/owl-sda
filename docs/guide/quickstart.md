# Quick Start

This guide walks you through generating synthetic RDF data for a minimal ontology.

You need a JDK 25 build and a working LLM backend. See [Installation](./installation) first.

## 1. Prepare your ontology

Create `input.ttl` — a minimal OWL ontology in Turtle format:

```turtle
@prefix owl:  <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex:   <http://example.org/> .

ex:Person a owl:Class ;
    rdfs:comment "A human being." .

ex:Organization a owl:Class ;
    rdfs:comment "A legal organisation." .

ex:worksFor a owl:ObjectProperty ;
    rdfs:domain ex:Person ;
    rdfs:range  ex:Organization .
```

## 2. Create the configuration

Create `config.yml` next to it:

```yaml
input-path: "input.ttl"
output-path: "output.ttl"
user-input: "Generate 5 example persons each working for one of 2 organisations."

client:
  provider: "copilot"
  worker:
    model: "gpt-5-mini"
    timeout-ms: 120000
    batch-size: 2
    pool-count: 1
  supervisor:
    model: "gpt-5.4"
    timeout-ms: 120000
  reviewer:
    model: "claude-4.6-sonnet"
    timeout-ms: 60000
```

`provider: "copilot"` is the default and drives the GitHub Copilot CLI. Switch it to `ollama` or `openai-compatible` if you would rather run against a local or self-hosted model — see the [Configuration Reference](./configuration).

::: warning
OWL-SDA deletes the file at `output-path` before every run. Point it at a fresh file, not at something you want to keep. A real run also spends real tokens on whichever backend you configured, so start small.
:::

## 3. Build and run

Build the executable jar (requires JDK 25):

```bash
mvn -DskipTests clean package
```

Then run it against your configuration. `--config` is **required** — running the jar without it prints a usage error rather than picking a default:

```bash
java -jar target/owlsda.jar --config config.yml
```

Two more things the CLI gives you:

```bash
java -jar target/owlsda.jar --help      # or -h: full option list
java -jar target/owlsda.jar --version   # or -V: prints 1.0-SNAPSHOT
```

`--config` accepts a plain path, or an explicit prefix:

| Value | Resolution |
|-------|-----------|
| `config.yml` | Classpath first, then the filesystem. |
| `file:config.yml` | Filesystem only. |
| `classpath:config.yml` | Classpath only. |

See the [CLI Reference](./cli) for every option.

## 4. Inspect the output

The triple store is flushed to `output.ttl` at each delegation round, before the supervisor inspects it, so you can watch intermediate progress:

```bash
cat output.ttl
```

To watch the run in a browser instead, enable benchmarking in your config and start the dashboard:

```yaml
benchmark:
  enabled: true
```

```bash
java -jar target/owlsda.jar --config config.yml --web-ui
```

It serves on `http://localhost:8080` (change it with `--web-ui-port`) and stays up after the run finishes until you press Ctrl+C. See the [Web UI](./web-ui) guide.

## Next Steps

- Run full, ready-to-use [Examples](./examples) (including benchmark visuals).
- Read the full [Configuration Reference](./configuration) to tune model selection, timeouts, and pool sizes.
- Enable [Benchmarking](./benchmarking) to track token usage and violation counts per round.
- Add [External Ontologies](./external-ontologies) to resolve `owl:imports` automatically.
