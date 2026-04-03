# Examples

## Project 1 — Brownie Production Facility

The `examples/project-1/` directory contains a full end-to-end example using a food/industrial process ontology.

### What it generates

Given the prompt:

> *"Create a data example for a brownie production facility. Introduce multiple versions of the operation in different years with different systems, processes and subprocesses."*

OWL-SDA generates an RDF dataset describing the facility's processes, systems, and temporal versions as instances of the classes defined in the ontology.

### Running the example

```bash
java -jar target/OWL-SDA-*.jar --config examples/project-1/config.yml
```

### Configuration highlights (`examples/project-1/config.yml`)

```yaml
client:
  worker:
    model: "gpt-5-mini"
    batch-size: 1
    pool-count: 5          # 5 parallel workers
  supervisor:
    model: "gpt-5.1"
  reviewer:
    model: "claude-3.5-sonnet"

reasoner:
  rules-file: "examples/project-1/reasoner.rules"
  reasoner-type: "owl"
  inferred-output-path: "examples/project-1/inferred.ttl"

shacl:
  output-dir: "examples/project-1/shacl/"

benchmark:
  enabled: true
  output-dir: "examples/project-1/benchmark/"
```

Key points:

- **5 parallel workers** run concurrently, each responsible for 1 shape per round.
- A **custom Jena rules file** (`reasoner.rules`) is used to derive additional inferences.
- SHACL shapes are **persisted** to avoid regeneration on subsequent runs.
- **Benchmarking** is enabled; results are stored in `examples/project-1/benchmark_paper/`.

### Benchmark results

The `examples/project-1/benchmark_paper/` directory contains pre-computed benchmark snapshots and a summary chart:

| File | Description |
|------|-------------|
| `benchmark-summary.json` | Machine-readable summary of all snapshots. |
| `benchmark_plot.pdf/png/svg` | Visual chart of shapes processed and violations over time. |

### Input files

| File | Description |
|------|-------------|
| `input.ttl` | The OWL ontology. |
| `input-example.txt` | Example data provided as user context to guide the LLM. |
| `reasoner.rules` | Custom Jena forward-chaining rules. |
| `inferred.ttl` | Cached inferred model (re-used on subsequent runs). |
| `shacl/default-shacl.ttl` | Cached SHACL shapes for the base model. |
| `shacl/inferred-shacl.ttl` | Cached SHACL shapes for the inferred model. |
| `external/` | Cached external ontologies (PROV-O, P-PLAN, SOSA, QUDT, …). |

### Output

After a successful run the generated data is written to `examples/project-1/output.ttl`.

