package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run-scoped key-value memory shared across worker/supervisor/reviewer sessions for the lifetime
 * of a single owlsda invocation. Lets agents stash short notes (intermediate findings, delegation
 * summaries, facts needed again later) without repeating them in every message. Cleared on
 * shutdown - not persisted across runs.
 */
public class RunMemoryStore {
  private static final Logger logger = LoggerFactory.getLogger(RunMemoryStore.class);

  private final Map<String, String> store = new LinkedHashMap<>();
  private final int maxEntries;
  private final int maxValueBytes;

  public RunMemoryStore(int maxEntries, int maxValueBytes) {
    this.maxEntries = maxEntries;
    this.maxValueBytes = maxValueBytes;
  }

  public synchronized PutResult put(String key, String value, String actorId) {
    if (key == null || key.isBlank()) {
      return PutResult.error("key is required");
    }
    if (value == null) {
      return PutResult.error("value is required");
    }
    if (value.length() > maxValueBytes) {
      return PutResult.error("value exceeds max-value-bytes (" + maxValueBytes + ")");
    }
    if (!store.containsKey(key) && store.size() >= maxEntries) {
      return PutResult.error("memory store is full (max-entries=" + maxEntries
          + "); remove or reuse an existing key");
    }

    store.put(key, value);
    logger.debug("[{}] memory_set '{}' ({} chars, {} total keys)", actorId, key, value.length(),
        store.size());
    return PutResult.success(store.size());
  }

  public synchronized Optional<String> get(String key) {
    return Optional.ofNullable(store.get(key));
  }

  public synchronized List<String> listKeys() {
    return new ArrayList<>(store.keySet());
  }

  public synchronized boolean remove(String key) {
    return store.remove(key) != null;
  }

  public synchronized void clear() {
    int sizeBefore = store.size();
    store.clear();
    if (sizeBefore > 0) {
      logger.info("Cleared {} entries from run memory store", sizeBefore);
    }
  }

  public static final class PutResult {
    private final boolean success;
    private final int totalEntries;
    private final String error;

    private PutResult(boolean success, int totalEntries, String error) {
      this.success = success;
      this.totalEntries = totalEntries;
      this.error = error;
    }

    static PutResult success(int totalEntries) {
      return new PutResult(true, totalEntries, null);
    }

    static PutResult error(String error) {
      return new PutResult(false, 0, error);
    }

    public boolean isSuccess() {
      return success;
    }

    public int getTotalEntries() {
      return totalEntries;
    }

    public String getError() {
      return error;
    }
  }
}
