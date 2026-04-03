# Introduction

**OWL-SDA** (OWL Synthetic Data AI-Agent) is a Java tool that generates synthetic RDF instance data from OWL ontologies using Large Language Models (LLMs).

Given an input ontology (in Turtle or RDF/XML format) and a natural-language prompt describing the desired data, OWL-SDA:

1. Loads and optionally reasons over the ontology (including fetching external imports).
2. Derives SHACL shapes from the OWL class descriptions.
3. Dispatches a **supervisor** agent that coordinates a pool of **worker** agents, each responsible for generating instances for a subset of SHACL shapes.
4. Validates the generated triples against the SHACL shapes after each round.
5. Passes the final output to a **reviewer** agent that either accepts it or requests targeted revisions.

## Key Concepts

### Agents

| Role | Responsibility |
|------|---------------|
| **Supervisor** | Orchestrates the generation loop, assigns shapes to workers, and finalises the output. |
| **Worker (pool)** | Generates RDF triples for its assigned shapes and writes them to the shared triple store. |
| **Reviewer** | Reviews the fully assembled output and signals ACCEPTED, REJECTED, or REVISION_REQUESTED. |

### SHACL Shapes

OWL-SDA automatically generates [SHACL](https://www.w3.org/TR/shacl/) NodeShapes from `owl:Class` definitions, including property shapes derived from `owl:Restriction` axioms. These shapes are used both to guide the workers and to validate the output after each generation round.

### Triple Store

Workers write triples directly into a shared in-memory triple store. After each delegation round the store is flushed to the configured output file so that the supervisor and reviewer can read it.

## Supported LLM Backends

Currently only **GitHub Copilot** is supported as a language model backend. Support for additional providers can be added by implementing the `Client` / `Session` interfaces.

