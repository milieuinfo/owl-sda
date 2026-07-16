# Benchmarking

OWL-SDA can capture a live snapshot of a run's state - message logs, contexts, triple store - as it happens, giving you full visibility into how the run is progressing without waiting for it to finish. There is exactly **one** benchmark folder per run: its files are overwritten in place every time a snapshot is captured, so you can watch `output-dir` while a run is in progress instead of finding results only after it completes. While a round or review iteration is still in progress, a background ticker captures a snapshot (stage `LIVE`) every `live-interval-seconds`, in addition to the snapshot captured at each round/review boundary.

## Enabling Benchmarking

```yaml
benchmark:
  enabled: true
  output-dir: "target/benchmarks"
  live-interval-seconds: 15
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `false` | Enable benchmark snapshot collection. |
| `output-dir` | string | `target/benchmarks` | Directory where the run's live snapshot and JSON summary are written. Cleared at the start of each run. |
| `live-interval-seconds` | long | `15` | How often, in seconds, a `LIVE` snapshot is captured while a round/review iteration is still running. |

## What Is Captured

All of the current run's state lives directly under `output-dir` (no per-round sub-directories) and is overwritten in place on every snapshot:

| File | Description |
|------|-------------|
| `metadata.txt` | Stage name, round number, shapes processed, duration, violation count, and per-agent token usage - reflects the most recent snapshot only. |
| `triplestore.ttl` | Full contents of the shared triple store at the time of the snapshot. |
| `triplestore-summary.txt` | Summary of triple store size. |
| `supervisor_context/` | All context entries active in the supervisor session. |
| `reviewer_context/` | All context entries active in the reviewer session. |
| `worker_contexts/worker_N/` | Context entries for each worker session. |
| `message_logs/` | Full JSON message transcripts for supervisor, reviewer, and all workers, as they stand right now. |
| `output.ttl` | Copy of the generated output file at snapshot time. |
| `owlsda.log` | Copy of the log file (only when `log-to-file: true`). |
| `benchmark-summary.json` | The one file that is *not* overwritten - see below. |

A snapshot is skipped (nothing is written) when nothing has changed since the previous one - by content hash of the message logs, contexts, triple store, and token counts - so a quiet run doesn't waste writes.

## JSON Summary

Every snapshot appends one entry to `benchmark-summary.json` in `output-dir` - this is the one file in the run that grows over time rather than being overwritten, because it's what preserves progress-over-time history for plotting even though the rest of the run directory only ever shows the latest state:

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
| `LIVE` | Every `live-interval-seconds` while a round/review iteration is still in progress. |
| `GENERATE` | After each supervisor delegation round. |
| `FINALIZING` | After the supervisor finalises the assembled output for consistency. |
| `REVIEW` | After the reviewer accepts or completes the review loop. |
| `REVIEW_ACCEPTED` | When the reviewer signals ACCEPTED. |
| `REVIEW_REJECTED` | When the reviewer signals REJECTED. |
| `REVIEW_ITERATION_N` | After each individual review revision iteration. |

## Web UI Dashboard

Pass `--web-ui` on the command line to start a local dashboard for watching a run live - messages, tool calls, stages, and output - instead of reading files off disk by hand:

```bash
java -jar target/owlsda.jar --config config.yml --web-ui
java -jar target/owlsda.jar --config config.yml --web-ui --web-ui-port 9090  # default: 8080
```

The dashboard reads `benchmark.output-dir` straight from disk on every request (no caching), so it reflects the run as it happens - it polls automatically every few seconds. It requires `benchmark.enabled: true`; without it there's nothing to show. It shows:

- **Stages** - the `benchmark-summary.json` history in the sidebar, newest first.
- **Messages** - the full transcript per role (supervisor, reviewer, each worker), with `TOOL_INVOCATION` entries parsed into a tool name and arguments where the format allows it.
- **Output** - the run's configured `output-path`.
- **Triple Store** - the live `triplestore.ttl`.

The server keeps running after the pipeline finishes so you can review the completed run; stop it with Ctrl+C.

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
