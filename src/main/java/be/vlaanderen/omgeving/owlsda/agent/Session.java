package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Represents a session within a large language model (LLM) context. */
public interface Session extends AutoCloseable {

  /**
   * Adds context to the session.
   *
   * @param context The context to be added.
   */
  void addContext(Context context);

  /**
   * Adds context to the session and returns whether it was actually added or changed.
   *
   * @param context The context to be added.
   * @return true if context was added or changed, false if it already existed with same content.
   */
  boolean addContextIfChanged(Context context);

  List<Context> getContext();

  /**
   * Prompts the session with the given input and returns the response.
   *
   * @param input The input to prompt the session with.
   * @param contexts The contexts to be included in the prompt.
   * @return The response from the session.
   */
  CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts);

  /**
   * Prompts the session with the given input and returns the response, using the session's existing
   * contexts.
   *
   * @param input The input to prompt the session with.
   * @return The response from the session.
   */
  CompletableFuture<ResponseMessage> prompt(RequestMessage input);

  /**
   * Returns a chronological log of messages sent to and received from this session. Implementations
   * can override this to expose full transcript data.
   */
  default List<SessionMessageLogEntry> getMessageLog() {
    return List.of();
  }

  /**
   * Returns cumulative input tokens sent to the model by this session. Implementations that cannot
   * provide this can keep the default value.
   */
  default long getInputTokensUsed() {
    return 0L;
  }

  /**
   * Returns cumulative output tokens received from the model by this session. Implementations that
   * cannot provide this can keep the default value.
   */
  default long getOutputTokensUsed() {
    return 0L;
  }

  /**
   * Returns the total token usage for this session. Implementations should return cumulative tokens
   * for the session lifetime.
   */
  default long getTotalTokensUsed() {
    return 0L;
  }

  /** Returns the timestamp (ms) of the most recent assistant-side activity for this session. */
  default long getLastAssistantActivityMs() {
    return System.currentTimeMillis();
  }

  /** Indicates whether this session has been idle for at least {@code idleThresholdMs}. */
  default boolean isIdleSince(long idleThresholdMs) {
    long elapsedMs = System.currentTimeMillis() - getLastAssistantActivityMs();
    return elapsedMs >= idleThresholdMs;
  }

  /**
   * Resets the session by clearing the server-side message history and recreating the underlying
   * LLM session. Contexts are preserved so the next prompt starts with a clean message history but
   * the same context window. This prevents context-window saturation across many delegation rounds
   * without losing the shared triplestore state or delegation metadata.
   */
  default void reset() {
    // No-op for implementations that do not support session reset.
  }

  /** Destroys the session, releasing any resources held by it. */
  void close();
}
