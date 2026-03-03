package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a session within a large language model (LLM) context.
 */
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
   * Prompts the session with the given input and returns the response, using the session's existing contexts.
   *
   * @param input The input to prompt the session with.
   * @return The response from the session.
   */
  CompletableFuture<ResponseMessage> prompt(RequestMessage input);

  /**
   * Returns a chronological log of messages sent to and received from this session.
   * Implementations can override this to expose full transcript data.
   */
  default List<SessionMessageLogEntry> getMessageLog() {
    return List.of();
  }

  /**
   * Returns the total token usage for this session.
   * Implementations should return cumulative tokens for the session lifetime.
   */
  default long getTotalTokensUsed() {
    return 0L;
  }

  /**
   * Destroys the session, releasing any resources held by it.
   */
  void close();
}
