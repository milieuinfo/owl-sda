package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing benchmark snapshots and timing measurements.
 */
@Slf4j
public class BenchmarkService {

  private final Config config;
  private int lastSnapshotHash = 0;

  public BenchmarkService(Config config) {
    this.config = config;
  }

  /**
   * Checks if benchmarking is enabled in the configuration.
   */
  public boolean isEnabled() {
    return config.getBenchmark() != null && config.getBenchmark().isEnabled();
  }

  /**
   * Checks if a snapshot should be created based on state changes.
   * The stage name is included in the hash so that lifecycle transitions
   * (e.g. GENERATE → FINALIZING → REVIEW) always produce distinct snapshots
   * even when session content has not changed.
   */
  private boolean shouldCreateSnapshot(String stage, Session generatorSession, Session reviewerSession,
      WorkerTripleStore tripleStore, List<Session> workerSessions) {
    if (!isEnabled()) {
      return false;
    }

    // Calculate hash of current state (stage-aware)
    int currentHash = calculateStateHash(stage, generatorSession, reviewerSession, tripleStore, workerSessions);

    // Check if state has changed since last snapshot
    if (currentHash == lastSnapshotHash && lastSnapshotHash != 0) {
      log.debug("No state changes detected, skipping snapshot creation");
      return false;
    }

    return true;
  }

  /**
   * Calculates a hash representing the current state of contexts, output, and triple store.
   * Includes the stage name so that distinct workflow stages always hash differently.
   * Handles null sessions gracefully for worker return snapshots.
   */
  private int calculateStateHash(String stage, Session generatorSession, Session reviewerSession,
      WorkerTripleStore tripleStore, List<Session> workerSessions) {
    int hash = 17;

    // Include the stage so GENERATE / FINALIZING / REVIEW always differ
    hash = 31 * hash + (stage != null ? stage.hashCode() : 0);

    // Hash generator contexts (if available)
    if (generatorSession != null) {
      for (Context c : generatorSession.getContext()) {
        String content = c.getContent();
        if (content != null) {
          hash = 31 * hash + content.hashCode();
        }
      }
    }

    // Hash reviewer contexts (if available)
    if (reviewerSession != null) {
      for (Context c : reviewerSession.getContext()) {
        String content = c.getContent();
        if (content != null) {
          hash = 31 * hash + content.hashCode();
        }
      }
    }

    // Hash worker delegation instructions (if available)
    if (workerSessions != null) {
      for (Session workerSession : workerSessions) {
        if (workerSession != null) {
          for (Context c : workerSession.getContext()) {
            String content = c.getContent();
            if (content != null) {
              hash = 31 * hash + content.hashCode();
            }
          }
        }
      }
    }

    // Hash output file if it exists
    try {
      String outputPath = config.getOutputPath();
      if (outputPath != null) {
        Path path = Path.of(outputPath);
        if (Files.exists(path)) {
          String outputContent = Files.readString(path);
          hash = 31 * hash + outputContent.hashCode();
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read output for state hash calculation", e);
    }

    // Hash triple store content
    if (tripleStore != null && tripleStore.size() > 0) {
      String tripleStoreContent = tripleStore.getAllTriples();
      hash = 31 * hash + tripleStoreContent.hashCode();
      hash = 31 * hash + (int) tripleStore.size();
    }

    // Hash session message transcripts so benchmark snapshots are created when chat activity changes.
    hash = 31 * hash + hashSessionMessageLog(generatorSession);
    hash = 31 * hash + hashSessionMessageLog(reviewerSession);

    if (workerSessions != null) {
      for (Session workerSession : workerSessions) {
        hash = 31 * hash + hashSessionMessageLog(workerSession);
      }
    }

    // Include token counters in state hash so snapshots capture token-usage deltas.
    hash = appendTokenHash(hash, generatorSession);
    hash = appendTokenHash(hash, reviewerSession);
    if (workerSessions != null) {
      for (Session workerSession : workerSessions) {
        hash = appendTokenHash(hash, workerSession);
      }
    }

    return hash;
  }

  private int appendTokenHash(int hash, Session session) {
    long inputTokens = session != null ? session.getInputTokensUsed() : 0L;
    long outputTokens = session != null ? session.getOutputTokensUsed() : 0L;
    long totalTokens = session != null ? session.getTotalTokensUsed() : 0L;

    hash = 31 * hash + Long.hashCode(inputTokens);
    hash = 31 * hash + Long.hashCode(outputTokens);
    hash = 31 * hash + Long.hashCode(totalTokens);
    return hash;
  }

  private int hashSessionMessageLog(Session session) {
    if (session == null) {
      return 0;
    }

    int hash = 1;
    for (SessionMessageLogEntry entry : session.getMessageLog()) {
      hash = 31 * hash + (entry.timestamp() != null ? entry.timestamp().hashCode() : 0);
      hash = 31 * hash + (entry.direction() != null ? entry.direction().hashCode() : 0);
      hash = 31 * hash + (entry.messageId() != null ? entry.messageId().hashCode() : 0);
      hash = 31 * hash + (entry.content() != null ? entry.content().hashCode() : 0);
    }
    return hash;
  }

  /**
   * Creates a snapshot for a batch with timing information.
   * Only creates snapshot if state has changed since last snapshot.
   */
  public String createBatchSnapshot(BenchmarkSnapshotData snapshotData, int currentViolations) {
    if (!shouldCreateSnapshot(
        snapshotData.stage(),
        snapshotData.generatorSession(),
        snapshotData.reviewerSession(),
        snapshotData.tripleStore(),
        snapshotData.workerSessions())) {
      return null;
    }

    try {
      long now = System.currentTimeMillis();
      String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
          .withLocale(Locale.US)
          .withZone(ZoneId.systemDefault())
          .format(Instant.ofEpochMilli(now));

      String baseOutput = config.getBenchmark().getOutputDir();
      Path snapshotDir = Path.of(baseOutput, timestamp);
      Files.createDirectories(snapshotDir);

      writeMetadata(
          snapshotDir,
          snapshotData.stage(),
          snapshotData.shapesProcessed(),
          snapshotData.durationMs(),
          timestamp,
          snapshotData.tripleStore(),
          currentViolations,
          snapshotData.generatorSession(),
          snapshotData.reviewerSession(),
          snapshotData.workerSessions()
      );

      writeSessionContexts(snapshotDir, snapshotData.generatorSession(), snapshotData.reviewerSession());
      writeWorkerContexts(snapshotDir, snapshotData.workerSessions(), snapshotData.round());
      writeSessionMessageLogs(
          snapshotDir,
          snapshotData.generatorSession(),
          snapshotData.reviewerSession(),
          snapshotData.workerSessions()
      );
      saveTripleStoreSnapshot(snapshotDir, snapshotData.tripleStore());
      copyOutputToSnapshot(snapshotDir);
      copyLogFileToSnapshot(snapshotDir);

      lastSnapshotHash = calculateStateHash(
          snapshotData.stage(),
          snapshotData.generatorSession(),
          snapshotData.reviewerSession(),
          snapshotData.tripleStore(),
          snapshotData.workerSessions());

      log.info("Benchmark snapshot created: {} (triple store: {} triples, violations: {})",
          timestamp,
          snapshotData.tripleStore() != null ? snapshotData.tripleStore().size() : 0,
          currentViolations);

      Path jsonFile = generateJsonSummary();
      if (jsonFile != null) {
        log.info("Benchmark JSON summary saved to: {}", jsonFile);
      }
      return timestamp;
    } catch (IOException e) {
      log.error("Error creating benchmark snapshot", e);
      return null;
    }
  }

  /**
   * Backward-compatible overload.
   */
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
            0,          // round not tracked in legacy call path
            shapesProcessed,
            durationMs,
            generatorSession,
            reviewerSession,
            tripleStore,
            workerSessions == null ? List.<Session>of() : workerSessions
        ),
        currentViolations
    );
  }

  /**
   * Writes metadata to metadata.txt in the snapshot directory.
   */
  private void writeMetadata(
      Path snapshotDir,
      String stage,
      int shapesProcessed,
      long durationMs,
      String timestamp,
      WorkerTripleStore tripleStore,
      int currentViolations,
      Session supervisorSession,
      Session reviewerSession,
      List<Session> workerSessions)
      throws IOException {
    Path meta = snapshotDir.resolve("metadata.txt");
    try (BufferedWriter w = Files.newBufferedWriter(meta)) {
      w.write("stage=" + (stage == null ? "GENERATE" : stage) + "\n");
      w.write("shapes_processed=" + shapesProcessed + "\n");
      w.write("duration_ms=" + durationMs + "\n");
      w.write("timestamp=" + timestamp + "\n");
      if (tripleStore != null) {
        w.write("triplestore_size=" + tripleStore.size() + "\n");
        w.write("triplestore_empty=" + (tripleStore.size() == 0) + "\n");
      }
      w.write("current_violations=" + currentViolations + "\n");

      writeRoleTokenUsage(w, "tokens.supervisor", supervisorSession);
      writeRoleTokenUsage(w, "tokens.reviewer", reviewerSession);

      if (workerSessions != null) {
        for (int i = 0; i < workerSessions.size(); i++) {
          writeRoleTokenUsage(w, "tokens.worker.worker_" + i, workerSessions.get(i));
        }
      }
    }
  }

  private void writeRoleTokenUsage(BufferedWriter writer, String keyPrefix, Session session)
      throws IOException {
    long inputTokens = session != null ? session.getInputTokensUsed() : 0L;
    long outputTokens = session != null ? session.getOutputTokensUsed() : 0L;
    long totalTokens = session != null ? session.getTotalTokensUsed() : 0L;

    writer.write(keyPrefix + ".input=" + inputTokens + "\n");
    writer.write(keyPrefix + ".output=" + outputTokens + "\n");
    writer.write(keyPrefix + ".total=" + totalTokens + "\n");
  }

  /**
   * Writes all session contexts to subdirectories in the snapshot.
   * Handles null sessions gracefully for worker return snapshots.
   */
  private void writeSessionContexts(Path snapshotDir, Session generatorSession, Session reviewerSession)
      throws IOException {

    if (generatorSession != null) {
      Path genDir = snapshotDir.resolve("supervisor_context");
      Files.createDirectories(genDir);
      writeContexts(genDir, generatorSession.getContext());
    }

    if (reviewerSession != null) {
      Path revDir = snapshotDir.resolve("reviewer_context");
      Files.createDirectories(revDir);
      writeContexts(revDir, reviewerSession.getContext());
    }
  }

  /**
   * Writes individual worker delegation instructions to subdirectories in the snapshot.
   * Each worker session's contexts are saved to a separate directory labeled with the worker index.
   * When {@code round} is greater than zero the context files are prefixed with {@code round_N-}
   * so that multiple delegation rounds within the same session can be distinguished.
   */
  private void writeWorkerContexts(Path snapshotDir, List<Session> workerSessions, int round)
      throws IOException {
    if (workerSessions == null || workerSessions.isEmpty()) {
      log.debug("No worker sessions to snapshot");
      return;
    }

    Path workersDir = snapshotDir.resolve("worker_contexts");
    Files.createDirectories(workersDir);

    String filePrefix = round > 0 ? "round_" + round + "-" : "";

    for (int i = 0; i < workerSessions.size(); i++) {
      Session workerSession = workerSessions.get(i);
      if (workerSession != null) {
        Path workerDir = workersDir.resolve("worker_" + i);
        Files.createDirectories(workerDir);
        writeContexts(workerDir, workerSession.getContext(), filePrefix);
      }
    }

    log.debug("Saved {} worker delegation contexts (round={})", workerSessions.size(), round);
  }

  /**
   * Writes a list of contexts to a directory.
   */
  private void writeContexts(Path targetDir, List<Context> contexts) throws IOException {
    writeContexts(targetDir, contexts, "");
  }

  /**
   * Writes a list of contexts to a directory, prepending {@code filePrefix} to each file name.
   */
  private void writeContexts(Path targetDir, List<Context> contexts, String filePrefix)
      throws IOException {
    int i = 0;
    for (Context c : contexts) {
      String name = c.getName() != null ? c.getName() : "context_" + i++;
      Path target = targetDir.resolve(filePrefix + safeFileName(name) + ".txt");
      String content = sanitizeForUtf8(c.getContent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    }
  }

  /**
   * Replaces invalid surrogate code units so text can be written as UTF-8.
   */
  private String sanitizeForUtf8(String content) {
    if (content == null || content.isEmpty()) {
      return "";
    }

    StringBuilder sanitized = null;
    int length = content.length();

    for (int i = 0; i < length; i++) {
      char ch = content.charAt(i);

      if (Character.isHighSurrogate(ch)) {
        if (i + 1 < length && Character.isLowSurrogate(content.charAt(i + 1))) {
          if (sanitized != null) {
            sanitized.append(ch).append(content.charAt(i + 1));
          }
          i++;
          continue;
        }
        if (sanitized == null) {
          sanitized = new StringBuilder(length);
          sanitized.append(content, 0, i);
        }
        sanitized.append('\uFFFD');
        continue;
      }

      if (Character.isLowSurrogate(ch)) {
        if (sanitized == null) {
          sanitized = new StringBuilder(length);
          sanitized.append(content, 0, i);
        }
        sanitized.append('\uFFFD');
        continue;
      }

      if (sanitized != null) {
        sanitized.append(ch);
      }
    }

    return sanitized == null ? content : sanitized.toString();
  }

  /**
   * Saves a snapshot of the shared triple store to the snapshot directory.
   */
  private void saveTripleStoreSnapshot(Path snapshotDir, WorkerTripleStore tripleStore) throws IOException {
    if (tripleStore == null) {
      log.debug("No triple store to snapshot");
      return;
    }

    if (tripleStore.size() == 0) {
      log.debug("Triple store is empty, creating empty snapshot file");
      Path tripleStoreFile = snapshotDir.resolve("triplestore.ttl");
      Files.writeString(tripleStoreFile, "# Triple store is empty\n");
      return;
    }

    try {
      String turtleData = tripleStore.getAllTriples();
      Path tripleStoreFile = snapshotDir.resolve("triplestore.ttl");
      Files.writeString(tripleStoreFile, turtleData);

      log.info("Saved triple store snapshot: {} triples", tripleStore.size());

      // Also save a summary
      Path summaryFile = snapshotDir.resolve("triplestore-summary.txt");
      try (BufferedWriter w = Files.newBufferedWriter(summaryFile)) {
        w.write("Triple Store Summary\n");
        w.write("===================\n\n");
        w.write("Total Triples: " + tripleStore.size() + "\n");
        w.write("Snapshot Time: " + Instant.now() + "\n");
        w.write("\nFull data available in: triplestore.ttl\n");
      }
    } catch (Exception e) {
      log.error("Failed to save triple store snapshot", e);
      // Create error marker file
      Path errorFile = snapshotDir.resolve("triplestore-error.txt");
      Files.writeString(errorFile, "Error saving triple store: " + e.getMessage());
    }
  }

  /**
   * Copies the output file to the snapshot directory.
   */
  private void copyOutputToSnapshot(Path snapshotDir) throws IOException {
    String outputPath = config.getOutputPath();
    if (outputPath == null) {
      return;
    }
    Path outputFile = Path.of(outputPath);
    if (Files.exists(outputFile)) {
      Files.copy(outputFile, snapshotDir.resolve(outputFile.getFileName()),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Copies the log file to the snapshot directory if log-to-file is enabled.
   */
  private void copyLogFileToSnapshot(Path snapshotDir) throws IOException {
    if (!config.isLogToFile()) {
      return;
    }
    Path logPath = Path.of(config.getLogFilePath());
    if (!Files.exists(logPath)) {
      return;
    }
    Files.copy(logPath, snapshotDir.resolve(logPath.getFileName()),
        StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Converts a name to a safe filename (removes special characters).
   */
  private String safeFileName(String name) {
    return name.replaceAll("[^a-zA-Z0-9_. -]", "_");
  }

  /**
   * Scans all benchmark snapshots in the configured output directory and generates a JSON summary.
   * The JSON file contains an array of all snapshots with their metadata.
   *
   * @return Path to the generated JSON file, or null if generation failed
   */
  public Path generateJsonSummary() {
    if (!isEnabled()) {
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

      log.info("Generated benchmark summary JSON with {} snapshots at: {}", snapshots.size(), jsonFile);
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
        String workerName = propertyName.substring("tokens.worker.".length(),
            propertyName.length() - ".total".length());
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

  /**
   * Safely parses an integer from a string, returning default value if parsing fails.
   */
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

  /**
   * Safely parses a long from a string, returning default value if parsing fails.
   */
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

  /**
   * Safely parses a boolean from a string, returning default value if parsing fails.
   */
  private boolean parseBoolean(String value) {
    if (value == null) {
      return true;
    }
    return Boolean.parseBoolean(value.trim());
  }

  /**
   * Writes persisted session message logs to the snapshot.
   * Supervisor, reviewer, and worker message logs are saved as JSON files.
   */
  private void writeSessionMessageLogs(
      Path snapshotDir,
      Session supervisorSession,
      Session reviewerSession,
      List<Session> workerSessions) throws IOException {
    Path logsDir = snapshotDir.resolve("message_logs");
    Files.createDirectories(logsDir);

    writeSingleSessionMessageLog(logsDir, "supervisor", supervisorSession);
    writeSingleSessionMessageLog(logsDir, "reviewer", reviewerSession);

    if (workerSessions != null) {
      for (int i = 0; i < workerSessions.size(); i++) {
        writeSingleSessionMessageLog(logsDir, "worker_" + i, workerSessions.get(i));
      }
    }
  }

  /**
   * Writes a single session's message log to a JSON file.
   */
  private void writeSingleSessionMessageLog(Path logsDir, String sessionName, Session session)
      throws IOException {
    Path target = logsDir.resolve(safeFileName(sessionName) + "-messages.json");

    if (session == null) {
      Files.writeString(target, "[]");
      return;
    }

    List<SessionMessageLogEntry> entries = session.getMessageLog();
    Files.writeString(target, JsonUtil.toJsonObject(entries));
  }
}
