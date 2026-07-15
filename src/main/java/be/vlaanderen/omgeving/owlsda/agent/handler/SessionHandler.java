package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a tool handler that can be registered with an LLM session. Each handler exposes a
 * named tool that the model can invoke, along with its argument schema and execution logic.
 */
public interface SessionHandler {

  /**
   * The tool's JSON-schema parameter definition, in the shape expected by the LLM provider's
   * function-calling API (an object with {@code type}, {@code properties}, and {@code required}).
   */
  Map<String, Object> getArguments();

  /** The tool name the model calls to invoke this handler; must be unique within a session. */
  String getName();

  /** A description shown to the model explaining what this tool does and when to use it. */
  String getDescription();

  /**
   * Executes the tool call. Implementations should not throw; a failure should be returned as a
   * result map (typically {@code {"error": "..."}}) so the model can react to it, since a failed
   * tool call is a normal outcome from the LLM's perspective, not an unrecoverable error.
   *
   * @param arguments the arguments the model supplied, matching {@link #getArguments()}'s schema
   * @return the tool result, serialized back to the model
   */
  CompletableFuture<Object> handle(Map<String, Object> arguments);

  /** Convenience for the common {@code {"error": message}} failure result. */
  default CompletableFuture<Object> errorResult(String message) {
    return CompletableFuture.completedFuture(Map.of("error", message));
  }
}
