package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/** Service for managing benchmark snapshots and timing measurements. */
@Slf4j
public class BenchmarkService {

  private final Config config;
  private final ChangeDetector changeDetector;
  private final SnapshotWriter snapshotWriter;
  private final SnapshotReader snapshotReader;

  public BenchmarkService(Config config) {
    this.config = config;
    this.changeDetector = new ChangeDetector(config);
    this.snapshotWriter = new SnapshotWriter(config);
    this.snapshotReader = new SnapshotReader(config);
  }

  /** Checks if benchmarking is enabled in the configuration. */
  public boolean isEnabled() {
    return config.getBenchmark() != null && config.getBenchmark().isEnabled();
  }

  /**
   * Updates the run's benchmark snapshot in place with timing information, and appends a history
   * entry to {@code benchmark-summary.json}. Only writes if state has changed since the last
   * snapshot. Unlike a versioned history, message logs, contexts, and the triple store are
   * overwritten in {@code output-dir} directly (one directory per run, not per snapshot) so the
   * run's live state can be inspected on disk while it's still in progress.
   */
  public String createBatchSnapshot(BenchmarkSnapshotData snapshotData, int currentViolations) {
    if (!isEnabled()) {
      return null;
    }
    if (!changeDetector.shouldCreateSnapshot(
        snapshotData.stage(),
        snapshotData.generatorSession(),
        snapshotData.reviewerSession(),
        snapshotData.tripleStore(),
        snapshotData.workerSessions())) {
      return null;
    }

    try {
      String snapshotId =
          DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
              .withLocale(Locale.US)
              .withZone(ZoneId.systemDefault())
              .format(Instant.ofEpochMilli(System.currentTimeMillis()));

      Path runDir = Path.of(config.getBenchmark().getOutputDir());
      Files.createDirectories(runDir);

      snapshotWriter.writeSnapshot(runDir, snapshotData, snapshotId, currentViolations);

      changeDetector.recordSnapshot(
          snapshotData.stage(),
          snapshotData.generatorSession(),
          snapshotData.reviewerSession(),
          snapshotData.tripleStore(),
          snapshotData.workerSessions());

      log.info(
          "Benchmark snapshot updated: stage={} (triple store: {} triples, violations: {})",
          snapshotData.stage(),
          snapshotData.tripleStore() != null ? snapshotData.tripleStore().size() : 0,
          currentViolations);

      Path jsonFile = snapshotReader.recordSnapshot(runDir);
      if (jsonFile != null) {
        log.info("Benchmark JSON summary updated: {}", jsonFile);
      }
      return snapshotId;
    } catch (IOException e) {
      log.error("Error updating benchmark snapshot", e);
      return null;
    }
  }

  /** Backward-compatible overload. */
  public String createBatchSnapshot(
      int shapesProcessed,
      long durationMs,
      Session generatorSession,
      Session reviewerSession,
      WorkerTripleStore tripleStore,
      List<Session> workerSessions,
      int currentViolations) {
    return createBatchSnapshot(
        new DefaultBenchmarkSnapshotData(
            "GENERATE", // Default stage for backward compatibility
            0, // round not tracked in legacy call path
            shapesProcessed,
            durationMs,
            generatorSession,
            reviewerSession,
            tripleStore,
            workerSessions == null ? List.<Session>of() : workerSessions),
        currentViolations);
  }

  /**
   * Returns the path to this run's {@code benchmark-summary.json} history file (an array of every
   * snapshot captured so far, appended to by {@link #createBatchSnapshot}), or null if benchmarking
   * is disabled or no snapshot has been captured yet.
   */
  public Path generateJsonSummary() {
    if (!isEnabled()) {
      return null;
    }
    Path jsonFile = Path.of(config.getBenchmark().getOutputDir()).resolve("benchmark-summary.json");
    return Files.exists(jsonFile) ? jsonFile : null;
  }
}
