package be.vlaanderen.omgeving.owlsda.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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

  private final static Logger logger = LoggerFactory.getLogger(SessionPool.class);

  private final List<Session> allSessions;
  private final BlockingQueue<SessionWithId> availableSessions;
  private final int poolSize;
  private final AtomicInteger nextSessionId = new AtomicInteger(0);
  private final Map<Session, String> sessionIdMap = new HashMap<>();
  /**
   *  Sets a callback to be notified when sessions are returned to the pool.
   *  Useful for triggering actions like benchmark snapshots.
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
    this.allSessions = new ArrayList<>(this.poolSize);
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
      availableSessions.offer(new SessionWithId(session, sessionId));
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
   * Returns a session to the pool for reuse. Logs the session ID to track when it becomes available
   * again. Triggers callback if set.
   *
   * @param session the session to return to the pool
   */
  public void returnSession(Session session) {
    if (session != null && allSessions.contains(session)) {
      String sessionId = sessionIdMap.getOrDefault(session, "UNKNOWN");
      SessionWithId sessionWithId = new SessionWithId(session, sessionId);
      availableSessions.offer(sessionWithId);
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
    return poolSize;
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

