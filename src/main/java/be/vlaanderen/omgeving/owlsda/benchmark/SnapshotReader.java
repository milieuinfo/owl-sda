package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads a run's current {@code metadata.txt} and appends it as one entry to {@code
 * benchmark-summary.json}, the growing history of every snapshot captured during the run. Unlike
 * the rest of a snapshot (message logs, contexts, triple store), this history file is additive
 * rather than overwritten in place, since it's what {@code scripts/plot_benchmark.py} reads to
 * chart progress over time.
 */
@Slf4j
class SnapshotReader {

  private final Config config;

  SnapshotReader(Config config) {
    this.config = config;
  }

  /**
   * Reads {@code runDir/metadata.txt} (just written by {@link SnapshotWriter}) and appends it to
   * {@code runDir/benchmark-summary.json}.
   *
   * @return Path to the updated JSON file, or null if benchmarking is disabled or no metadata
   *     exists yet.
   */
  Path recordSnapshot(Path runDir) {
    if (config.getBenchmark() == null || !config.getBenchmark().isEnabled()) {
      log.warn("Benchmarking is not enabled, cannot record snapshot history");
      return null;
    }

    Path metadataFile = runDir.resolve("metadata.txt");
    if (!Files.exists(metadataFile)) {
      log.warn("No metadata.txt found in {}, cannot record snapshot history", runDir);
      return null;
    }

    BenchmarkSnapshot latest = readSnapshotMetadata(metadataFile);
    if (latest == null) {
      return null;
    }

    try {
      Path jsonFile = runDir.resolve("benchmark-summary.json");
      List<BenchmarkSnapshot> history = readExistingHistory(jsonFile);
      history.add(latest);

      Files.writeString(jsonFile, JsonUtil.toJson(history));
      log.info(
          "Appended benchmark snapshot to summary ({} total) at: {}", history.size(), jsonFile);
      return jsonFile;
    } catch (IOException e) {
      log.error("Error recording benchmark snapshot history", e);
      return null;
    }
  }

  private List<BenchmarkSnapshot> readExistingHistory(Path jsonFile) {
    if (!Files.exists(jsonFile)) {
      return new ArrayList<>();
    }
    try {
      String content = Files.readString(jsonFile);
      return new ArrayList<>(JsonUtil.fromJsonSnapshotList(content));
    } catch (Exception e) {
      log.warn(
          "Failed to read existing benchmark summary at {}, starting a new history: {}",
          jsonFile,
          e.getMessage());
      return new ArrayList<>();
    }
  }

  /** Reads metadata from a {@code metadata.txt} file. */
  private BenchmarkSnapshot readSnapshotMetadata(Path metadataFile) {
    try {
      Properties props = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(metadataFile)) {
        props.load(reader);
      }

      return BenchmarkSnapshot.builder()
          .timestamp(props.getProperty("timestamp", "unknown"))
          .stage(props.getProperty("stage", "GENERATE"))
          .shapesProcessed(parseInt(props.getProperty("shapes_processed"), 0))
          .durationMs(parseLong(props.getProperty("duration_ms")))
          .triplestoreSize(parseLong(props.getProperty("triplestore_size")))
          .triplestoreEmpty(parseBoolean(props.getProperty("triplestore_empty")))
          .currentViolations(parseInt(props.getProperty("current_violations"), 0))
          .tokens(readTokenUsage(props))
          .build();
    } catch (IOException e) {
      log.warn("Failed to read metadata from {}: {}", metadataFile, e.getMessage());
      return null;
    }
  }

  private BenchmarkTokenUsage readTokenUsage(Properties props) {
    Map<String, BenchmarkRoleTokenUsage> workers = new LinkedHashMap<>();

    for (String propertyName : props.stringPropertyNames()) {
      if (propertyName.startsWith("tokens.worker.") && propertyName.endsWith(".total")) {
        String workerName =
            propertyName.substring(
                "tokens.worker.".length(), propertyName.length() - ".total".length());
        workers.put(workerName, readRoleTokenUsage(props, "tokens.worker." + workerName));
      }
    }

    for (String propertyName : props.stringPropertyNames()) {
      if (propertyName.startsWith("tokens.worker.")) {
        String suffix = propertyName.substring("tokens.worker.".length());
        String workerName;

        if (suffix.endsWith(".input")) {
          workerName = suffix.substring(0, suffix.length() - ".input".length());
        } else if (suffix.endsWith(".output")) {
          workerName = suffix.substring(0, suffix.length() - ".output".length());
        } else if (suffix.endsWith(".total")) {
          workerName = suffix.substring(0, suffix.length() - ".total".length());
        } else {
          workerName = suffix;
        }

        workers.putIfAbsent(workerName, readRoleTokenUsage(props, "tokens.worker." + workerName));
      }
    }

    return BenchmarkTokenUsage.builder()
        .workers(workers)
        .reviewer(readRoleTokenUsage(props, "tokens.reviewer"))
        .supervisor(readRoleTokenUsage(props, "tokens.supervisor"))
        .build();
  }

  private BenchmarkRoleTokenUsage readRoleTokenUsage(Properties props, String keyPrefix) {
    boolean hasInput = props.containsKey(keyPrefix + ".input");
    boolean hasOutput = props.containsKey(keyPrefix + ".output");
    boolean hasTotal = props.containsKey(keyPrefix + ".total");

    long input = parseLong(props.getProperty(keyPrefix + ".input"));
    long output = parseLong(props.getProperty(keyPrefix + ".output"));

    long total;
    if (hasTotal) {
      total = parseLong(props.getProperty(keyPrefix + ".total"));
    } else if (hasInput || hasOutput) {
      total = input + output;
    } else {
      total = parseLong(props.getProperty(keyPrefix));
    }

    return BenchmarkRoleTokenUsage.fromValues(input, output, total);
  }

  /** Safely parses an integer from a string, returning default value if parsing fails. */
  private int parseInt(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** Safely parses a long from a string, returning default value if parsing fails. */
  private long parseLong(String value) {
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** Safely parses a boolean from a string, returning default value if parsing fails. */
  private boolean parseBoolean(String value) {
    if (value == null) {
      return true;
    }
    return Boolean.parseBoolean(value.trim());
  }
}
