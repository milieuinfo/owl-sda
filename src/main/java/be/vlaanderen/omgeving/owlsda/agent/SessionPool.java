package be.vlaanderen.omgeving.owlsda.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a pool of generator sessions that work concurrently on different batches of shapes.
 * Sessions can be borrowed from the pool and returned after processing.
 * <p>
 * Provides detailed logging to track which session is processing what, making it easy to identify
 * concurrent execution in log files. Each session has a unique identifier (POOL-0, POOL-1, etc.)
 */
@Getter
public class SessionPool implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(SessionPool.class);

  private final List<Session> allSessions;
  private final BlockingQueue<SessionWithId> availableSessions;
  private final int poolSize;
  private final AtomicInteger nextSessionId = new AtomicInteger(0);
  private final Map<Session, String> sessionIdMap = new HashMap<>();
  /**
   * Sets a callback to be notified when sessions are returned to the pool.
   * Useful for triggering actions like benchmark snapshots.
   */
  @Setter
  private SessionReturnCallback sessionReturnCallback;

  /**
   * Callback interface for notifications when a session is returned to the pool.
   */
  public interface SessionReturnCallback {
    void onSessionReturned(Session session, String sessionId);
  }

  @Override
  public void close() {
    availableSessions.clear();
    allSessions.clear();
    sessionIdMap.clear();
    logger.info("Session pool shut down ({} sessions removed)", poolSize);
  }

  /**
   * Represents a session with its unique identifier for logging purposes. This allows tracking of
   * which session is being used in concurrent scenarios.
   */
  public record SessionWithId(Session session, String sessionId) {

  }

  public SessionPool(int poolSize) {
    this.poolSize = Math.max(1, poolSize);
    this.allSessions = new CopyOnWriteArrayList<>();
    this.availableSessions = new ArrayBlockingQueue<>(this.poolSize);
  }

  /**
   * Adds a session to the pool with an auto-generated ID. Session IDs follow the format: POOL-0,
   * POOL-1, POOL-2, etc.
   *
   * @param session the session to add to the pool
   */
  public void addSession(Session session) {
    if (allSessions.size() < poolSize) {
      String sessionId = String.format("POOL-%d", nextSessionId.getAndIncrement());
      allSessions.add(session);
      sessionIdMap.put(session, sessionId);
      boolean queued = availableSessions.offer(new SessionWithId(session, sessionId));
      if (!queued) {
        logger.error("[{}] Failed to enqueue newly added session", sessionId);
      }
      logger.info("[{}] Session added to pool ({}/{})", sessionId, allSessions.size(), poolSize);
    } else {
      logger.warn("Pool is already full ({} sessions), cannot add more sessions", poolSize);
    }
  }

  /**
   * Borrows a session from the pool. Blocks if no session is available. Logs the session ID and
   * requesting thread name for debugging concurrent execution.
   *
   * @return a SessionWithId containing an available session and its identifier
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public SessionWithId borrowSession() throws InterruptedException {
    SessionWithId sessionWithId = availableSessions.take();
    String threadId = Thread.currentThread().getName();
    logger.info("[{}] Borrowed by thread [{}] ({} sessions still available)",
        sessionWithId.sessionId, threadId, availableSessions.size());
    return sessionWithId;
  }

  /**
   * Borrows a specific session from the pool by ID. Blocks until that session becomes available.
   * Non-matching borrowed sessions are temporarily held and then returned to the queue.
   *
   * @param sessionId target session ID (for example: POOL-0)
   * @return the requested SessionWithId
   * @throws InterruptedException if interrupted while waiting
   */
  public SessionWithId borrowSessionById(String sessionId) throws InterruptedException {
    if (sessionId == null || sessionId.isBlank()) {
      return borrowSession();
    }

    // Avoid indefinite waits caused by invalid target IDs.
    if (!sessionIdMap.containsValue(sessionId)) {
      logger.warn("Unknown target session '{}'; falling back to generic borrow", sessionId);
      return borrowSession();
    }

    int retries = 0;
    while (true) {
      SessionWithId candidate = availableSessions.take();
      if (sessionId.equals(candidate.sessionId())) {
        String threadId = Thread.currentThread().getName();
        logger.info("[{}] Borrowed by thread [{}] via targeted borrow ({} sessions still available)",
            candidate.sessionId(), threadId, availableSessions.size());
        return candidate;
      }

      // Never keep non-target sessions out of the queue; other workers may need them.
      boolean requeued = availableSessions.offer(candidate);
      if (!requeued) {
        logger.error("Failed to requeue non-target session {} while waiting for {}",
            candidate.sessionId(), sessionId);
      }
      retries++;
      if (retries % 50 == 0) {
        logger.debug("Waiting for targeted session {} (attempts: {}, available: {})",
            sessionId, retries, availableSessions.size());
      }
      Thread.yield();
    }
  }

  /**
   * Returns a session to the pool for reuse. Logs the session ID to track when it becomes available
   * again. Triggers callback if set.
   *
   * @param session the session to return to the pool
   */
  public void returnSession(Session session) {
    if (session != null && allSessions.contains(session)) {
      String sessionId = sessionIdMap.getOrDefault(session, "UNKNOWN");
      SessionWithId sessionWithId = new SessionWithId(session, sessionId);
      boolean queued = availableSessions.offer(sessionWithId);
      if (!queued) {
        logger.error("[{}] Failed to return session to pool; queue is unexpectedly full", sessionId);
      }
      logger.info("[{}] Returned to pool ({} sessions now available)",
          sessionId, availableSessions.size());

      // Trigger callback if set (e.g., for benchmark snapshots)
      if (sessionReturnCallback != null) {
        try {
          sessionReturnCallback.onSessionReturned(session, sessionId);
        } catch (Exception e) {
          logger.error("[{}] Error in session return callback: {}", sessionId, e.getMessage());
        }
      }
    } else {
      logger.warn("Attempted to return a session that is not part of the pool");
    }
  }

  /**
   * Gets the total number of sessions in the pool.
   *
   * @return the pool size
   */
  public int getSize() {
    return allSessions.size();
  }

  /**
   * Gets the number of available sessions ready for use.
   *
   * @return the number of available sessions
   */
  public int getAvailableCount() {
    return availableSessions.size();
  }

  /**
   * Gets the unique identifier for a session.
   *
   * @param session the session to get the ID for
   * @return the session identifier (e.g., "POOL-0")
   */
  public String getSessionId(Session session) {
    return sessionIdMap.getOrDefault(session, "UNKNOWN");
  }
}
