package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/** Scans benchmark snapshot directories on disk and assembles a JSON summary of all of them. */
@Slf4j
class SnapshotReader {

  private final Config config;

  SnapshotReader(Config config) {
    this.config = config;
  }

  /**
   * Scans all benchmark snapshots in the configured output directory and generates a JSON summary.
   * The JSON file contains an array of all snapshots with their metadata.
   *
   * @return Path to the generated JSON file, or null if generation failed
   */
  Path generateJsonSummary() {
    if (config.getBenchmark() == null || !config.getBenchmark().isEnabled()) {
      log.warn("Benchmarking is not enabled, cannot generate JSON summary");
      return null;
    }

    try {
      String baseOutput = config.getBenchmark().getOutputDir();
      Path outputDir = Path.of(baseOutput);

      if (!Files.exists(outputDir)) {
        log.warn("Benchmark output directory does not exist: {}", outputDir);
        return null;
      }

      List<BenchmarkSnapshot> snapshots = scanSnapshots(outputDir);

      if (snapshots.isEmpty()) {
        log.info("No benchmark snapshots found in {}", outputDir);
        return null;
      }

      Path jsonFile = outputDir.resolve("benchmark-summary.json");
      String jsonContent = JsonUtil.toJson(snapshots);
      Files.writeString(jsonFile, jsonContent);

      log.info(
          "Generated benchmark summary JSON with {} snapshots at: {}", snapshots.size(), jsonFile);
      return jsonFile;
    } catch (IOException e) {
      log.error("Error generating benchmark JSON summary", e);
      return null;
    }
  }

  /**
   * Scans the benchmark output directory for all snapshot folders and reads their metadata.
   *
   * @param outputDir The benchmark output directory to scan
   * @return List of BenchmarkSnapshot objects
   */
  private List<BenchmarkSnapshot> scanSnapshots(Path outputDir) throws IOException {
    List<BenchmarkSnapshot> snapshots = new ArrayList<>();

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
      for (Path entry : stream) {
        if (Files.isDirectory(entry)) {
          Path metadataFile = entry.resolve("metadata.txt");
          if (Files.exists(metadataFile)) {
            BenchmarkSnapshot snapshot = readSnapshotMetadata(entry, metadataFile);
            if (snapshot != null) {
              snapshots.add(snapshot);
            }
          }
        }
      }
    }

    // Sort by timestamp
    snapshots.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

    return snapshots;
  }

  /**
   * Reads metadata from a benchmark snapshot directory.
   *
   * @param snapshotDir The snapshot directory
   * @param metadataFile The metadata.txt file
   * @return BenchmarkSnapshot object or null if reading failed
   */
  private BenchmarkSnapshot readSnapshotMetadata(Path snapshotDir, Path metadataFile) {
    try {
      Properties props = new Properties();
      try (BufferedReader reader = Files.newBufferedReader(metadataFile)) {
        props.load(reader);
      }

      return BenchmarkSnapshot.builder()
          .timestamp(props.getProperty("timestamp", "unknown"))
          .stage(props.getProperty("stage", "GENERATE"))
          .snapshotDirectory(snapshotDir.getFileName().toString())
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
