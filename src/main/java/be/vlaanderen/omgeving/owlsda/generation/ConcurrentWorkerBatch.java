package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs worker batches concurrently across the worker session pool, using a long-lived pooled
 * executor since instances of this class are constructed once and reused across every delegation
 * round.
 */
public class ConcurrentWorkerBatch {

  private static final Logger logger = LoggerFactory.getLogger(ConcurrentWorkerBatch.class);
  private static final long MONITOR_INTERVAL_MS = 5000L;
  private static final long STUCK_JOIN_GRACE_MS = 10000L;

  private final Config config;
  private final SessionPool workerSessionPool;
  private final Shacl shacl;
  private final OutputValidator validator;
  private final ExecutorService executor;

  public ConcurrentWorkerBatch(
      Config config, SessionPool workerSessionPool, Shacl shacl, OutputValidator validator) {
    this.config = config;
    this.workerSessionPool = workerSessionPool;
    this.shacl = shacl;
    this.validator = validator;

    int poolSize = workerSessionPool != null ? Math.max(1, workerSessionPool.getSize()) : 1;
    this.executor = Executors.newFixedThreadPool(poolSize);
  }

  public Config config() {
    return config;
  }

  public SessionPool workerSessionPool() {
    return workerSessionPool;
  }

  public Shacl shacl() {
    return shacl;
  }

  public OutputValidator validator() {
    return validator;
  }

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

    int totalUnprocessedShapes =
        (int) shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).count();

    int workerCount = workerSessionPool.getSize();
    int batchSize = (config != null) ? config.getBatchSize() : 1;
    int actualShapes = totalAssignableShapes(shapes, totalUnprocessedShapes);

    List<Future<?>> futures = new ArrayList<>();
    List<AtomicBoolean> results = new ArrayList<>();

    for (int i = 0; i < workerCount; i++) {
      int[] range = workerShapeRange(i, batchSize, actualShapes);
      int startIndex = range[0];
      int endIndex = range[1];

      AtomicBoolean workerSuccess = new AtomicBoolean(false);
      results.add(workerSuccess);

      WorkerAgent worker =
          new WorkerAgent(
              workerSessionPool,
              shacl,
              i,
              workerCount,
              startIndex,
              endIndex,
              actualShapes,
              instructions,
              workerSuccess,
              isDelegationMode);

      futures.add(executor.submit(worker));
    }

    boolean stuckWorkersDetected = false;

    // Monitor worker progress periodically
    while (futures.stream().anyMatch(future -> !future.isDone())) {
      Thread.sleep(MONITOR_INTERVAL_MS);
      logWorkerActivityStatus();

      // Break if any worker has been idle > 2x their configured timeout.
      if (hasStuckWorkers()) {
        logger.warn("Detected worker(s) stuck/idle for extended time; interrupting active workers");
        stuckWorkersDetected = true;
        cancelUnfinishedWorkers(futures);
        break;
      }
    }

    boolean hasUnfinishedWorkers = awaitWorkersWithTimeout(futures, STUCK_JOIN_GRACE_MS);
    if (hasUnfinishedWorkers) {
      logger.error(
          "One or more workers did not stop within {}ms after cancellation", STUCK_JOIN_GRACE_MS);
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
      logger.debug(
          "Store still has validation issues after worker batch; continuing to next round");
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

  /**
   * Caps the requested shape count to what's actually available. Shared with {@link
   * Supervisor#calculateOptimalDistribution} so its delegation-hint text can never diverge from
   * what {@link #run} actually assigns to workers.
   */
  static int totalAssignableShapes(int requestedShapes, int availableShapes) {
    return Math.min(requestedShapes, availableShapes);
  }

  /**
   * Computes the {@code [start, end)} shape index range assigned to {@code workerIndex}, given
   * {@code batchSize} shapes per worker out of {@code assignableShapes} available. Shared with
   * {@link Supervisor#calculateOptimalDistribution} for the same reason as {@link
   * #totalAssignableShapes}.
   */
  static int[] workerShapeRange(int workerIndex, int batchSize, int assignableShapes) {
    int startIndex = workerIndex * batchSize;
    int endIndex = Math.min(startIndex + batchSize, assignableShapes);
    return new int[] {startIndex, endIndex};
  }

  /**
   * Shuts down the pooled executor. Should be called exactly once, from the owning teardown path,
   * since this batch's executor is long-lived and shared across every delegation round.
   */
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(STUCK_JOIN_GRACE_MS, TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void logWorkerActivityStatus() {
    if (workerSessionPool == null) {
      return;
    }

    List<Session> allSessions = workerSessionPool.getAllSessions();
    for (int i = 0; i < allSessions.size(); i++) {
      Session session = allSessions.get(i);
      long lastActivityMs = session.getLastAssistantActivityMs();
      long elapsedMs = System.currentTimeMillis() - lastActivityMs;
      int messageCount = session.getMessageLog().size();
      logger.debug("POOL-{}: {} messages, last activity {} ms ago", i, messageCount, elapsedMs);
    }
  }

  private void cancelUnfinishedWorkers(List<Future<?>> futures) {
    for (Future<?> future : futures) {
      if (!future.isDone()) {
        future.cancel(true);
      }
    }
  }

  private boolean awaitWorkersWithTimeout(List<Future<?>> futures, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    boolean unfinished = false;

    for (Future<?> future : futures) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        if (!future.isDone()) {
          unfinished = true;
        }
        continue;
      }

      try {
        future.get(remaining, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        unfinished = true;
      } catch (ExecutionException e) {
        // Worker failure is tracked via its AtomicBoolean result, not propagated here.
        logger.warn("Worker task completed with an exception: {}", e.getMessage());
      } catch (CancellationException e) {
        // Expected when a worker was cancelled after being detected as stuck.
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
      // A session already returned to the pool has no in-progress work this round; its idle time
      // (simply waiting for the next round) must not trigger cancellation of other sessions that
      // are still legitimately processing.
      if (workerSessionPool.isAvailable(session)) {
        continue;
      }
      if (session.isIdleSince(stuckThresholdMs)) {
        logger.warn(
            "Worker {} has been idle for {}ms (threshold: {}ms)",
            workerSessionPool.getSessionId(session),
            stuckThresholdMs,
            stuckThresholdMs);
        return true;
      }
    }

    return false;
  }
}
