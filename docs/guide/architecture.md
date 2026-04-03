# Architecture

## Overview

OWL-SDA follows a **multi-agent supervisor-worker** pattern. A central `SupervisorWorkflow` drives the entire generation pipeline and coordinates three types of agents:

```
┌─────────────────────────────────────────────────────────────┐
│                     SupervisorWorkflow                      │
│                                                             │
│  ┌───────────────┐   delegates   ┌────────────────────────┐ │
│  │   Supervisor  │ ─────────────▶│   Worker Pool (N)      │ │
│  │   (session)   │               │   POOL-0 … POOL-(N-1)  │ │
│  └───────────────┘               └────────────────────────┘ │
│         │                                   │               │
│         │ review                            │ writes        │
│         ▼                                   ▼               │
│  ┌───────────────┐          ┌───────────────────────────┐   │
│  │   Reviewer    │          │   WorkerTripleStore        │   │
│  │   (session)   │          │   (shared, in-memory)      │   │
│  └───────────────┘          └───────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Generation Pipeline

### 1. Initialisation (`OWLSDA`)

- The input OWL ontology is loaded from disk.
- External ontologies referenced via `owl:imports` are fetched/cached by `OntologyExtractor`.
- An optional reasoner (`OntologyReasoner`) is applied, producing an inferred model.
- SHACL shapes are generated from `owl:Class` definitions for both the base and inferred model.
- Sessions (workers, supervisor, reviewer) are created via the `SessionManager`.
- A `WorkerTripleStore` (shared among all workers) is initialised.

### 2. Generation Rounds (`SupervisorWorkflow`)

The workflow repeatedly calls `Supervisor.orchestrate()` until all shapes are processed and validation is clean:

1. **Delegation** — The supervisor is prompted with the list of unprocessed SHACL shapes and the current validation report. It calls `delegate_tasks` for each worker, assigning a subset of shapes.
2. **Worker execution** — All delegated workers run concurrently. Each worker adds triples to the shared triple store via `triplestore_add`.
3. **Completion evaluation** — After the batch completes, `ShapeCompletionEvaluator` checks which shapes now have instances in the store and no remaining SHACL violations; those shapes are marked as processed.
4. **Scope expansion** — Once shapes are completing cleanly the scope is expanded (more shapes per round) using the configured `batch-size × pool-count`.

### 3. Finalisation

When all shapes are processed and the store is clean:

- If `pool-count > 1`, the supervisor performs a final editing pass over the assembled output to enforce consistency and documentation quality.

### 4. Review

A reviewer session reads the full output and calls `output_feedback` with one of:

| State | Meaning |
|-------|---------|
| `ACCEPTED` | Output is ready; pipeline ends successfully. |
| `REJECTED` | Generation failed; pipeline ends with an error. |
| `REVISION_REQUESTED` | Specific feedback is passed back to the supervisor for targeted fixes. |

Up to `MAX_REVIEW_ITERATIONS` (3) revision cycles are attempted before the pipeline terminates.

## Key Classes

| Class | Package | Role |
|-------|---------|------|
| `OWLSDA` | root | Top-level orchestrator; wires all components together. |
| `SupervisorWorkflow` | generation | Drives the overall pipeline loop. |
| `Supervisor` | generation | Delegates shapes to workers, marks completion, finalises output. |
| `SupervisorReviewCoordinator` | generation | Runs the review loop between the reviewer and supervisor. |
| `ConcurrentWorkerBatch` | generation | Runs all worker threads for a single delegation round. |
| `WorkerAgent` | generation | Runnable that drives a single worker session for one round. |
| `SessionManager` | agent | Creates and manages all LLM sessions and their tool handlers. |
| `SessionPool` | agent | Thread-safe pool of worker sessions. |
| `WorkerTripleStore` | agent/handler | Shared in-memory RDF store written to by all workers. |
| `OntologyExtractor` | ontology | Fetches and caches external ontologies via HTTP. |
| `OntologyReasoner` | ontology | Applies Jena reasoning to derive implicit inferences. |
| `Shacl` | ontology | Generates, loads, saves, and validates SHACL shapes. |
| `BenchmarkService` | benchmark | Captures and persists per-round benchmark snapshots. |

## Tool Handlers

Each agent session is equipped with a set of tool handlers (implementations of `SessionHandler`) that the LLM can invoke:

| Handler | Available to | Purpose |
|---------|-------------|---------|
| `ContextReaderHandler` | all | Read named context entries. |
| `DelegationHandler` | supervisor | Publish instructions to a specific worker via `delegate_tasks`. |
| `TripleStoreAddHandler` | workers | Add Turtle triples to the shared store. |
| `TripleStoreRemoveHandler` | workers | Remove triples from the shared store. |
| `TripleStoreReadHandler` | workers | Query triples from the shared store. |
| `TripleStoreClearHandler` | workers | Clear all triples from the shared store. |
| `OutputWriterHandler` | supervisor | Write/overwrite the output file. |
| `OutputAppendHandler` | supervisor | Append to the output file. |
| `OutputReplaceHandler` | supervisor | Replace specific line ranges in the output file. |
| `OutputReaderHandler` | all | Read the current output file contents. |
| `OutputValidatorHandler` | all | Validate Turtle data against the SHACL shapes. |
| `OutputFeedbackHandler` | reviewer | Signal ACCEPTED / REJECTED / REVISION_REQUESTED. |
| `ShapeStatusCheckerHandler` | supervisor | Query which shapes are pending / completed. |
| `WorkerProgressHandler` | workers | Write a structured progress report context entry. |

