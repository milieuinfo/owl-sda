/**
 * Records per-round benchmark data (token usage, timing, triple counts, validation state) when
 * {@code benchmark.enabled} is set. {@link
 * be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService} is the entry point used by {@code
 * generation}; the remaining classes are the data model persisted to {@code
 * examples/*&#47;benchmark/} and the {@code benchmark-summary.json} produced at the end of a run.
 * See {@code docs/guide/benchmarking.md}.
 */
package be.vlaanderen.omgeving.owlsda.benchmark;
