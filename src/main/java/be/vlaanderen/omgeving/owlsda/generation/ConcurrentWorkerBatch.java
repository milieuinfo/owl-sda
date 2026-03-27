package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs worker batches concurrently across the worker session pool.
 */
public record ConcurrentWorkerBatch(Config config, SessionPool workerSessionPool, Shacl shacl,
                                    OutputValidator validator) {

  private static final Logger logger = LoggerFactory.getLogger(ConcurrentWorkerBatch.class);
  private static final long MONITOR_INTERVAL_MS = 5000L;
  private static final long STUCK_JOIN_GRACE_MS = 10000L;

  public boolean run(String instructions, int shapes, boolean isDelegationMode)
      throws InterruptedException {
    if (shacl == null || shacl.getShapes() == null) {
      logger.error("Cannot run batch: SHACL or shapes are null");
      return false;
    }

    if (workerSessionPool == null) {
      logger.error("Cannot run batch: Worker session pool is null");
      return false;
    }

    int totalUnprocessedShapes = (int) shacl.getShapes().stream()
        .filter(shape -> !shape.isProcessed())
        .count();

    int workerCount = workerSessionPool.getSize();
    int batchSize = (config != null) ? config.getBatchSize() : 1;
    int actualShapes = Math.min(shapes, totalUnprocessedShapes);

    List<Thread> threads = new ArrayList<>();
    List<AtomicBoolean> results = new ArrayList<>();

    for (int i = 0; i < workerCount; i++) {
      int startIndex = i * batchSize;
      int endIndex = Math.min(startIndex + batchSize, actualShapes);

      AtomicBoolean workerSuccess = new AtomicBoolean(false);
      results.add(workerSuccess);

      WorkerAgent worker = new WorkerAgent(
          workerSessionPool, shacl,
          i, workerCount,
          startIndex, endIndex,
          actualShapes,
          instructions,
          workerSuccess,
          isDelegationMode
      );

      Thread thread = new Thread(worker);
      threads.add(thread);
      thread.start();
    }

    boolean stuckWorkersDetected = false;

    // Monitor worker progress periodically
    while (threads.stream().anyMatch(Thread::isAlive)) {
      Thread.sleep(MONITOR_INTERVAL_MS);
      logWorkerActivityStatus();

      // Break if any worker has been idle > 2x their configured timeout.
      if (hasStuckWorkers()) {
        logger.warn("Detected worker(s) stuck/idle for extended time; interrupting active workers");
        stuckWorkersDetected = true;
        interruptAliveWorkers(threads);
        break;
      }
    }

    boolean hasUnfinishedWorkers = joinWorkersWithTimeout(threads, STUCK_JOIN_GRACE_MS);
    if (hasUnfinishedWorkers) {
      logger.error("One or more worker threads did not stop within {}ms after interruption",
          STUCK_JOIN_GRACE_MS);
      return false;
    }

    if (stuckWorkersDetected) {
      logger.warn("Worker batch failed due to stuck/idle worker detection");
      return false;
    }

    boolean allSuccess = results.stream().allMatch(AtomicBoolean::get);
    if (!allSuccess) {
      logger.warn("One or more workers failed");
      return false;
    }

    if (validator == null) {
      return true;
    }

    // Validation remains informational here; global store validity is tracked separately.
    String validationReport = validator.validate();
    if (validationReport != null && !validationReport.isBlank()) {
      logger.debug("Store still has validation issues after worker batch; continuing to next round");
    }
    return true;
  }

  public boolean runWithDelegation(String instructions, int shapes) throws InterruptedException {
    // Delegation mode: workers should not receive new SHACL shapes, only work with existing data
    return run(instructions, shapes, true);
  }

  public int getWorkerCount() {
    return workerSessionPool.getSize();
  }

  private void logWorkerActivityStatus() {
    if (workerSessionPool == null) {
      return;
    }

    List<Session> allSessions = workerSessionPool.getAllSessions();
    for (int i = 0; i < allSessions.size(); i++) {
      Session session = allSessions.get(i);
      if (session instanceof be.vlaanderen.omgeving.owlsda.agent.copilot.CopilotSDKSession copilotSession) {
        long lastActivityMs = copilotSession.getLastAssistantActivityMs();
        long elapsedMs = System.currentTimeMillis() - lastActivityMs;
        int messageCount = copilotSession.getMessageLog().size();
        logger.debug("POOL-{}: {} messages, last activity {} ms ago",
            i, messageCount, elapsedMs);
      }
    }
  }

  private void interruptAliveWorkers(List<Thread> threads) {
    for (Thread thread : threads) {
      if (thread.isAlive()) {
        thread.interrupt();
      }
    }
  }

  private boolean joinWorkersWithTimeout(List<Thread> threads, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    boolean unfinished = false;

    for (Thread thread : threads) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        if (thread.isAlive()) {
          unfinished = true;
        }
        continue;
      }

      thread.join(remaining);
      if (thread.isAlive()) {
        unfinished = true;
      }
    }

    return unfinished;
  }

  private boolean hasStuckWorkers() {
    if (workerSessionPool == null || config == null) {
      return false;
    }

    long workerTimeoutMs = config.getClient().getWorker().getTimeoutMs();
    long stuckThresholdMs = workerTimeoutMs * 2; // 2x timeout = stuck

    List<Session> allSessions = workerSessionPool.getAllSessions();
    for (Session session : allSessions) {
      if (session instanceof be.vlaanderen.omgeving.owlsda.agent.copilot.CopilotSDKSession copilotSession) {
        if (copilotSession.isIdleSince(stuckThresholdMs)) {
          logger.warn("Worker {} has been idle for {}ms (threshold: {}ms)",
              workerSessionPool.getSessionId(session), stuckThresholdMs, stuckThresholdMs);
          return true;
        }
      }
    }

    return false;
  }
}
