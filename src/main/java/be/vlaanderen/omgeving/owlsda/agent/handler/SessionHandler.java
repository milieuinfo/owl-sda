package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a tool handler that can be registered with an LLM session.
 * Each handler exposes a named tool that the model can invoke, along with
 * its argument schema and execution logic.
 */
public interface SessionHandler {

  Map<String, Object> getArguments();

  String getName();

  String getDescription();

  CompletableFuture<Object> handle(Map<String, Object> arguments);
}
