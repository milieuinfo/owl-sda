package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Retrieves a note previously stored with {@code memory_set}, or lists the available keys. */
public record MemoryGetHandler(RunMemoryStore memoryStore) implements SessionHandler {

  public static final String NAME = "memory_get";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Retrieve a note previously stored with memory_set by its key. Omit 'key' to list all
        keys currently stored in shared run memory.
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
                    "description", "Name the value was stored under (omit to list all keys)")),
        "required", List.of());
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String key = (String) arguments.get("key");

    if (key == null || key.isBlank()) {
      return CompletableFuture.completedFuture(Map.of("keys", memoryStore.listKeys()));
    }

    Optional<String> value = memoryStore.get(key);
    if (value.isEmpty()) {
      return CompletableFuture.completedFuture(Map.of("found", false, "key", key));
    }

    return CompletableFuture.completedFuture(
        Map.of("found", true, "key", key, "value", value.get()));
  }
}
