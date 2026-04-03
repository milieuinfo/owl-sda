# Quick Start

This guide walks you through generating synthetic RDF data for a minimal ontology.

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

Create `config.yml`:

```yaml
input-path: "input.ttl"
output-path: "output.ttl"
user-input: "Generate 5 example persons each working for one of 2 organisations."

client:
  worker:
    model: "gpt-5.1-mini"
    timeout-ms: 120000
    batch-size: 2
    pool-count: 1
  supervisor:
    model: "gpt-5.1"
    timeout-ms: 120000
  reviewer:
    model: "gpt-5.1"
    timeout-ms: 60000
```

## 3. Run

```bash
java -jar OWL-SDA.jar --config config.yml
```

## 4. Inspect the output

The generated triples are written to `output.ttl` after every worker round, so you can inspect intermediate progress.

```bash
cat output.ttl
```

## Next Steps

- Read the full [Configuration Reference](./configuration) to tune model selection, timeouts, and pool sizes.
- Enable [Benchmarking](./benchmarking) to track token usage and violation counts per round.
- Add [External Ontologies](./external-ontologies) to resolve `owl:imports` automatically.

