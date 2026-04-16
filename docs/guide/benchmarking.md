# Benchmarking

OWL-SDA can capture detailed snapshots after every generation round, giving you full visibility into how the run progressed.

## Enabling Benchmarking

```yaml
benchmark:
  enabled: true
  output-dir: "target/benchmarks"
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `false` | Enable benchmark snapshot collection. |
| `output-dir` | string | `target/benchmarks` | Directory where snapshot folders and the JSON summary are written. |

## What Is Captured

Each snapshot is written to a timestamped sub-directory (e.g. `20260320_113645_535/`) inside `output-dir` and contains:

| File | Description |
|------|-------------|
| `metadata.txt` | Stage name, round number, shapes processed, duration, violation count, and per-agent token usage. |
| `triplestore.ttl` | Full contents of the shared triple store at the time of the snapshot. |
| `triplestore-summary.txt` | Summary of triple store size. |
| `supervisor_context/` | All context entries active in the supervisor session. |
| `reviewer_context/` | All context entries active in the reviewer session. |
| `worker_contexts/worker_N/` | Context entries for each worker session. |
| `message_logs/` | Full JSON message transcripts for supervisor, reviewer, and all workers. |
| `output.ttl` | Copy of the generated output file at snapshot time. |
| `owlsda.log` | Copy of the log file (only when `log-to-file: true`). |

Snapshots are deduplicated by content hash - if nothing changed between two rounds no duplicate snapshot is written.

## JSON Summary

After every snapshot a `benchmark-summary.json` file is (re)generated in `output-dir`. It contains an array of all snapshots sorted by timestamp, making it easy to track progress over time:

```json
[
  {
    "timestamp": "20260407_090654_779",
    "stage": "GENERATE",
    "shapesProcessed": 5,
    "durationMs": 174572,
    "currentViolations": 6,
    "triplestoreSize": 84,
    "tokens": {
      "supervisor": 24990,
      "reviewer": 0,
      "workers": {
        "worker_0": 13569,
        "worker_1": 11382,
        "worker_2": 14494,
        "worker_3": 10911,
        "worker_4": 13724
      }
    }
  }
]
```

## Stages

| Stage | When it occurs |
|-------|---------------|
| `GENERATE` | After each supervisor delegation round. |
| `FINALIZING` | After the supervisor finalises the assembled output for consistency. |
| `REVIEW` | After the reviewer accepts or completes the review loop. |
| `REVIEW_ACCEPTED` | When the reviewer signals ACCEPTED. |
| `REVIEW_REJECTED` | When the reviewer signals REJECTED. |
| `REVIEW_ITERATION_N` | After each individual review revision iteration. |

## Visualising Results

A Python plotting script is included in `scripts/plot_benchmark.py` and produces PDF/PNG/SVG charts from a JSON summary file:

```bash
python scripts/plot_benchmark.py examples/project-1/benchmark_paper/benchmark-summary.json
python scripts/plot_benchmark.py examples/project-2/benchmark_paper/benchmark-summary.json
```

You can inspect generated sample visuals directly in the [Examples](./examples) page, or use these embedded charts:

### Example benchmark chart - Project 1

![Project 1 benchmark chart](/benchmarks/project-1-benchmark.svg)

### Example benchmark chart - Project 2

![Project 2 benchmark chart](/benchmarks/project-2-benchmark.svg)

Raw benchmark data used for these images:

- `examples/project-1/benchmark_paper/benchmark-summary.json`
- `examples/project-2/benchmark_paper/benchmark-summary.json`
