package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for clearing all triples from the shared triple store. Use with extreme caution - this
 * affects all workers.
 */
public record TripleStoreClearHandler(WorkerTripleStore tripleStore, String workerId)
    implements SessionHandler {

  public static final String NAME = "triplestore_clear";

  private static final Logger logger = LoggerFactory.getLogger(TripleStoreClearHandler.class);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Clear ALL triples from the SHARED triple store.

        ⚠️ EXTREME CAUTION:
        - This clears the SHARED store used by ALL workers
        - This will delete triples added by other workers
        - Only use if you need to completely restart
        - Coordinate with other workers before using

        This operation cannot be undone.
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "confirm",
                Map.of(
                    "type", "boolean",
                    "description",
                        "Set to true to confirm clearing ALL triples from SHARED store")),
        "required", List.of("confirm"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    Boolean confirm = (Boolean) arguments.get("confirm");

    if (confirm == null || !confirm) {
      return CompletableFuture.completedFuture(
          Map.of(
              "error",
              "Clear operation requires confirmation. Set 'confirm' to true. "
                  + "WARNING: This clears the SHARED store affecting all workers."));
    }

    try {
      long sizeBefore = tripleStore.size();
      tripleStore.clear();

      logger.warn("[{}] CLEARED shared triple store ({} triples removed)", workerId, sizeBefore);

      return CompletableFuture.completedFuture(
          Map.of(
              "status",
              "success",
              "triples_removed",
              sizeBefore,
              "message",
              String.format(
                  "⚠️ Cleared %d triples from SHARED store. All workers affected.", sizeBefore)));
    } catch (Exception e) {
      logger.error("[{}] Failed to clear shared triple store", workerId, e);
      return errorResult("Failed to clear triple store: " + e.getMessage());
    }
  }
}
