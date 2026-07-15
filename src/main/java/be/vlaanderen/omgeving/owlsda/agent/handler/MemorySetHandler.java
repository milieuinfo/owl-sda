package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.handler.RunMemoryStore.PutResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stores a short note in the shared run-scoped memory, keyed by name, so other agents (or this same
 * agent in a later round) can retrieve it with {@code memory_get} instead of repeating it in every
 * message.
 */
public record MemorySetHandler(RunMemoryStore memoryStore, String actorId)
    implements SessionHandler {

  public static final String NAME = "memory_set";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Store a short note (such as intermediate findings, a delegation summary, or a fact you'll
        need again) in shared run memory, keyed by name. Other agents (workers, supervisor) can
        retrieve it with memory_get using the same key.

        This memory is NOT part of the conversation history and does not get repeated in every
        message - use it instead of restating the same information over and over.
        It is cleared when this run ends.
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "key",
                    Map.of(
                        "type", "string",
                        "description", "Name to store the value under"),
                "value",
                    Map.of(
                        "type", "string",
                        "description",
                            "Value to store (plain text; JSON-encode it yourself if structured)")),
        "required", List.of("key", "value"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String key = (String) arguments.get("key");
    String value = (String) arguments.get("value");

    PutResult result = memoryStore.put(key, value, actorId);
    if (!result.isSuccess()) {
      return errorResult(result.getError());
    }

    return CompletableFuture.completedFuture(
        Map.of("status", "success", "key", key, "total_entries", result.getTotalEntries()));
  }
}
