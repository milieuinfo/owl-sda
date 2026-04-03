# Reasoner

OWL-SDA can apply an OWL or RDFS reasoner to the input ontology before generating SHACL shapes. Reasoning infers implicit subclass relationships and property restrictions that are not explicit in the ontology, producing richer shapes for the worker agents.

## Configuration

```yaml
reasoner:
  reasoner-type: "owl"          # owl | rdfs | transitive (default: owl)
  rules-file: ""                # optional path to a Jena rules file
  reasoner-timeout-ms: 0        # 0 = no timeout
  inferred-output-path: ""      # optional path to save the inferred model
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `reasoner-type` | string | `owl` | Built-in reasoner to use when no custom rules file is specified. |
| `rules-file` | string | `""` | Path to a [Jena rules file](https://jena.apache.org/documentation/inference/#rules). When set this takes precedence over `reasoner-type`. |
| `reasoner-timeout-ms` | long | `0` | Timeout for the reasoning step in milliseconds. `0` disables the timeout. |
| `inferred-output-path` | string | `""` | If set, the inferred model is serialised to this file. On subsequent runs with the same path the file is loaded directly, skipping re-inference. |

## Reasoner Types

| Value | Apache Jena Reasoner | Notes |
|-------|---------------------|-------|
| `owl` | `OWLMicroReasoner` | Handles most OWL axioms; recommended for standard ontologies. |
| `rdfs` | `RDFSReasoner` | Faster and lighter; handles `rdfs:subClassOf`, `rdfs:domain`, `rdfs:range` only. |
| `transitive` | `TransitiveReasoner` | Only transitive closure; useful for deeply nested class hierarchies. |

## Custom Rules

For fine-grained control you can supply a Jena forward-chaining rules file:

```yaml
reasoner:
  rules-file: "rules/my-rules.jena"
```

Example rule:

```
[rule1: (?x rdf:type ex:Employee) -> (?x rdf:type ex:Person)]
```

Refer to the [Jena rules documentation](https://jena.apache.org/documentation/inference/#rules) for the full syntax.

## Caching Inferred Models

Reasoning can be slow for large ontologies. Set `inferred-output-path` to persist the result:

```yaml
reasoner:
  reasoner-type: "owl"
  inferred-output-path: "target/inferred.ttl"
```

On the first run OWL-SDA performs inference and writes the result to `target/inferred.ttl`. On subsequent runs it detects the file and skips re-inference. Delete the file to force re-inference.

