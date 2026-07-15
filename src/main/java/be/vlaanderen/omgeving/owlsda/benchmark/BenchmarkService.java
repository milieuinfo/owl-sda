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
   * Creates a snapshot for a batch with timing information. Only creates snapshot if state has
   * changed since last snapshot.
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
      long now = System.currentTimeMillis();
      String timestamp =
          DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
              .withLocale(Locale.US)
              .withZone(ZoneId.systemDefault())
              .format(Instant.ofEpochMilli(now));

      String baseOutput = config.getBenchmark().getOutputDir();
      String snapshotId = timestamp;
      Path snapshotDir = Path.of(baseOutput, snapshotId);
      // Millisecond-resolution timestamps can collide when snapshots are created in rapid
      // succession (e.g. back-to-back stage transitions); disambiguate instead of silently
      // overwriting an earlier snapshot's directory.
      for (int suffix = 1; Files.exists(snapshotDir); suffix++) {
        snapshotId = timestamp + "-" + suffix;
        snapshotDir = Path.of(baseOutput, snapshotId);
      }
      Files.createDirectories(snapshotDir);

      snapshotWriter.writeSnapshot(snapshotDir, snapshotData, snapshotId, currentViolations);

      changeDetector.recordSnapshot(
          snapshotData.stage(),
          snapshotData.generatorSession(),
          snapshotData.reviewerSession(),
          snapshotData.tripleStore(),
          snapshotData.workerSessions());

      log.info(
          "Benchmark snapshot created: {} (triple store: {} triples, violations: {})",
          snapshotId,
          snapshotData.tripleStore() != null ? snapshotData.tripleStore().size() : 0,
          currentViolations);

      Path jsonFile = generateJsonSummary();
      if (jsonFile != null) {
        log.info("Benchmark JSON summary saved to: {}", jsonFile);
      }
      return snapshotId;
    } catch (IOException e) {
      log.error("Error creating benchmark snapshot", e);
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
   * Scans all benchmark snapshots in the configured output directory and generates a JSON summary.
   * The JSON file contains an array of all snapshots with their metadata.
   *
   * @return Path to the generated JSON file, or null if generation failed
   */
  public Path generateJsonSummary() {
    return snapshotReader.generateJsonSummary();
  }
}
