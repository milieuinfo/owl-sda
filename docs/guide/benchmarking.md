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

Snapshots are deduplicated by content hash — if nothing changed between two rounds no duplicate snapshot is written.

## JSON Summary

After every snapshot a `benchmark-summary.json` file is (re)generated in `output-dir`. It contains an array of all snapshots sorted by timestamp, making it easy to track progress over time:

```json
[
  {
    "timestamp": "20260320_113645_535",
    "stage": "GENERATE",
    "snapshotDirectory": "20260320_113645_535",
    "shapesProcessed": 3,
    "durationMs": 12340,
    "currentViolations": 2,
    "triplestoreSize": 47,
    "tokens": {
      "supervisor": 4210,
      "reviewer": 0,
      "workers": { "worker_0": 3100, "worker_1": 2800 }
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

A Python plotting script is included in `scripts/plot_benchmark.py` and produces a PDF/PNG/SVG chart from the JSON summary file:

```bash
cd scripts
pip install matplotlib
python plot_benchmark.py ../examples/project-1/benchmark_paper/benchmark-summary.json
```

