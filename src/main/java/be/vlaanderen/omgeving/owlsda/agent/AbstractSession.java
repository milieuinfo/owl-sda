package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared bookkeeping for {@link Session} implementations: context storage/dedup, token counters,
 * assistant-activity tracking, and the message log. Provider-specific request/response handling
 * (HTTP chat completions, the Copilot SDK's own session/event model) stays in the concrete
 * subclass.
 */
public abstract class AbstractSession implements Session {

  private final Set<Context> contexts = ConcurrentHashMap.newKeySet();
  private final List<SessionMessageLogEntry> messageLog = new CopyOnWriteArrayList<>();
  protected final AtomicLong totalTokensUsed = new AtomicLong(0L);
  protected final AtomicLong inputTokensUsed = new AtomicLong(0L);
  protected final AtomicLong outputTokensUsed = new AtomicLong(0L);
  private final AtomicLong lastAssistantActivityMs = new AtomicLong(System.currentTimeMillis());
  private final AtomicBoolean busy = new AtomicBoolean(false);

  /** Wraps a context before it is stored; override to store a {@link Context} subclass. */
  protected Context wrapContext(Context context) {
    return new Context(context);
  }

  /** Exposes a stored context via {@link #getContext()}; override to skip defensive copying. */
  protected Context exposeContext(Context stored) {
    return new Context(stored);
  }

  @Override
  public void addContext(Context context) {
    synchronized (contexts) {
      contexts.removeIf(context::equals);
      contexts.add(wrapContext(context));
    }
  }

  @Override
  public boolean addContextIfChanged(Context context) {
    synchronized (contexts) {
      Context existingContext = contexts.stream().filter(context::equals).findFirst().orElse(null);

      if (existingContext == null) {
        contexts.add(wrapContext(context));
        return true;
      }

      String existingContent = existingContext.getContent();
      String newContent = context.getContent();
      if (newContent == null && existingContent == null) {
        return false;
      }

      if (newContent == null || !newContent.equals(existingContent)) {
        contexts.removeIf(context::equals);
        contexts.add(wrapContext(context));
        return true;
      }

      return false;
    }
  }

  @Override
  public List<Context> getContext() {
    synchronized (contexts) {
      return contexts.stream().map(this::exposeContext).toList();
    }
  }

  @Override
  public List<SessionMessageLogEntry> getMessageLog() {
    return List.copyOf(messageLog);
  }

  protected void addMessageLogEntry(String direction, String messageId, String content) {
    messageLog.add(
        new SessionMessageLogEntry(
            Instant.now().toString(), direction, messageId, content == null ? "" : content));
  }

  @Override
  public long getTotalTokensUsed() {
    long directionalTotal = inputTokensUsed.get() + outputTokensUsed.get();
    return Math.max(totalTokensUsed.get(), directionalTotal);
  }

  @Override
  public long getInputTokensUsed() {
    return inputTokensUsed.get();
  }

  @Override
  public long getOutputTokensUsed() {
    return outputTokensUsed.get();
  }

  @Override
  public long getLastAssistantActivityMs() {
    return lastAssistantActivityMs.get();
  }

  @Override
  public boolean isIdleSince(long idleThresholdMs) {
    long elapsedMs = System.currentTimeMillis() - lastAssistantActivityMs.get();
    return elapsedMs >= idleThresholdMs;
  }

  protected void markAssistantActivity() {
    lastAssistantActivityMs.set(System.currentTimeMillis());
  }

  @Override
  public boolean isBusy() {
    return busy.get();
  }

  /** Marks a prompt as in flight; pair with {@link #markPromptFinished()} in a try/finally. */
  protected void markPromptStarted() {
    busy.set(true);
  }

  /** Clears the in-flight flag set by {@link #markPromptStarted()}. */
  protected void markPromptFinished() {
    busy.set(false);
  }
}
