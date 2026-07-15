package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Writes a benchmark snapshot's metadata, contexts, triple store, and message logs to disk. */
@Slf4j
class SnapshotWriter {

  private final Config config;

  SnapshotWriter(Config config) {
    this.config = config;
  }

  /** Writes every part of a snapshot into an already-created {@code snapshotDir}. */
  void writeSnapshot(
      Path snapshotDir, BenchmarkSnapshotData snapshotData, String snapshotId, int currentViolations)
      throws IOException {
    writeMetadata(
        snapshotDir,
        snapshotData.stage(),
        snapshotData.shapesProcessed(),
        snapshotData.durationMs(),
        snapshotId,
        snapshotData.tripleStore(),
        currentViolations,
        snapshotData.generatorSession(),
        snapshotData.reviewerSession(),
        snapshotData.workerSessions());

    writeSessionContexts(
        snapshotDir, snapshotData.generatorSession(), snapshotData.reviewerSession());
    writeWorkerContexts(snapshotDir, snapshotData.workerSessions(), snapshotData.round());
    writeSessionMessageLogs(
        snapshotDir,
        snapshotData.generatorSession(),
        snapshotData.reviewerSession(),
        snapshotData.workerSessions());
    saveTripleStoreSnapshot(snapshotDir, snapshotData.tripleStore());
    copyOutputToSnapshot(snapshotDir);
    copyLogFileToSnapshot(snapshotDir);
  }

  /** Writes metadata to metadata.txt in the snapshot directory. */
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
   * Writes all session contexts to subdirectories in the snapshot. Handles null sessions gracefully
   * for worker return snapshots.
   */
  private void writeSessionContexts(
      Path snapshotDir, Session generatorSession, Session reviewerSession) throws IOException {

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
   * Writes individual worker delegation instructions to subdirectories in the snapshot. Each worker
   * session's contexts are saved to a separate directory labeled with the worker index. When {@code
   * round} is greater than zero the context files are prefixed with {@code round_N-} so that
   * multiple delegation rounds within the same session can be distinguished.
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

  /** Writes a list of contexts to a directory. */
  private void writeContexts(Path targetDir, List<Context> contexts) throws IOException {
    writeContexts(targetDir, contexts, "");
  }

  /** Writes a list of contexts to a directory, prepending {@code filePrefix} to each file name. */
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

  /** Replaces invalid surrogate code units so text can be written as UTF-8. */
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

  /** Saves a snapshot of the shared triple store to the snapshot directory. */
  private void saveTripleStoreSnapshot(Path snapshotDir, WorkerTripleStore tripleStore)
      throws IOException {
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

  /** Copies the output file to the snapshot directory. */
  private void copyOutputToSnapshot(Path snapshotDir) throws IOException {
    String outputPath = config.getOutputPath();
    if (outputPath == null) {
      return;
    }
    Path outputFile = Path.of(outputPath);
    if (Files.exists(outputFile)) {
      Files.copy(
          outputFile,
          snapshotDir.resolve(outputFile.getFileName()),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Copies the log file to the snapshot directory if log-to-file is enabled. */
  private void copyLogFileToSnapshot(Path snapshotDir) throws IOException {
    if (!config.isLogToFile()) {
      return;
    }
    Path logPath = Path.of(config.getLogFilePath());
    if (!Files.exists(logPath)) {
      return;
    }
    Files.copy(
        logPath, snapshotDir.resolve(logPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
  }

  /** Converts a name to a safe filename (removes special characters). */
  private String safeFileName(String name) {
    return name.replaceAll("[^a-zA-Z0-9_. -]", "_");
  }

  /**
   * Writes persisted session message logs to the snapshot. Supervisor, reviewer, and worker message
   * logs are saved as JSON files.
   */
  private void writeSessionMessageLogs(
      Path snapshotDir,
      Session supervisorSession,
      Session reviewerSession,
      List<Session> workerSessions)
      throws IOException {
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

  /** Writes a single session's message log to a JSON file. */
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
