package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Hashes the observable state of a benchmark snapshot (contexts, output, triple store, message
 * logs, token counters) to detect whether anything changed since the last snapshot was taken.
 */
@Slf4j
class ChangeDetector {

  private final Config config;
  private int lastSnapshotHash = 0;

  ChangeDetector(Config config) {
    this.config = config;
  }

  /**
   * Checks if a snapshot should be created based on state changes. The stage name is included in
   * the hash so that lifecycle transitions (e.g. GENERATE → FINALIZING → REVIEW) always produce
   * distinct snapshots even when session content has not changed.
   */
  boolean shouldCreateSnapshot(
      String stage,
      Session generatorSession,
      Session reviewerSession,
      WorkerTripleStore tripleStore,
      List<Session> workerSessions) {
    int currentHash =
        calculateStateHash(stage, generatorSession, reviewerSession, tripleStore, workerSessions);

    if (currentHash == lastSnapshotHash && lastSnapshotHash != 0) {
      log.debug("No state changes detected, skipping snapshot creation");
      return false;
    }

    return true;
  }

  /** Records the current state hash as the baseline for future {@link #shouldCreateSnapshot}. */
  void recordSnapshot(
      String stage,
      Session generatorSession,
      Session reviewerSession,
      WorkerTripleStore tripleStore,
      List<Session> workerSessions) {
    lastSnapshotHash =
        calculateStateHash(stage, generatorSession, reviewerSession, tripleStore, workerSessions);
  }

  /**
   * Calculates a hash representing the current state of contexts, output, and triple store.
   * Includes the stage name so that distinct workflow stages always hash differently. Handles null
   * sessions gracefully for worker return snapshots.
   */
  private int calculateStateHash(
      String stage,
      Session generatorSession,
      Session reviewerSession,
      WorkerTripleStore tripleStore,
      List<Session> workerSessions) {
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

    // Hash session message transcripts so benchmark snapshots are created when chat activity
    // changes.
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
}
