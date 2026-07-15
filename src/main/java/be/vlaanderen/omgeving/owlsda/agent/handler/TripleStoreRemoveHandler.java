package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for removing triples from the shared triple store. Supports pattern-based removal using
 * subject, predicate, and object patterns.
 */
public record TripleStoreRemoveHandler(WorkerTripleStore tripleStore, String workerId)
    implements SessionHandler {

  public static final String NAME = "triplestore_remove";

  private static final Logger logger = LoggerFactory.getLogger(TripleStoreRemoveHandler.class);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Remove RDF triples from the SHARED triple store.
        Specify patterns using subject, predicate, and/or object.
        Use null or omit parameters to match any value (wildcard).

        IMPORTANT:
        - The store is shared across all workers
        - Removing triples affects all workers
        - Blank nodes linked to removed triples are automatically cleaned up
        - Orphaned blank nodes (no longer referenced by named resources) are removed recursively
        - Be careful when removing - coordinate with other workers
        - Use triplestore_read to verify what exists before removing

        Examples:
        - Remove all triples about a subject: {subject: "http://example.org/Person1"}
        - Remove specific property: {subject: "http://example.org/Person1", predicate: "http://example.org/age"}
        - Remove all triples with a predicate: {predicate: "http://example.org/age"}
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "subject",
                    Map.of(
                        "type", "string",
                        "description", "Subject URI to match (optional, null = wildcard)"),
                "predicate",
                    Map.of(
                        "type", "string",
                        "description", "Predicate URI to match (optional, null = wildcard)"),
                "object",
                    Map.of(
                        "type", "string",
                        "description",
                            "Object URI or literal to match (optional, null = wildcard)")),
        "required", List.of());
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String subject = (String) arguments.get("subject");
    String predicate = (String) arguments.get("predicate");
    String object = (String) arguments.get("object");

    try {
      int triplesRemoved =
          tripleStore.removeTriplesWithBlankNodes(subject, predicate, object, workerId);
      long totalTriples = tripleStore.size();

      String message =
          String.format(
              "Removed %d triples (including any orphaned blank nodes). Shared store now contains %d triples.",
              triplesRemoved, totalTriples);

      return CompletableFuture.completedFuture(
          Map.of(
              "status", "success",
              "triples_removed", triplesRemoved,
              "total_triples", totalTriples,
              "message", message));
    } catch (Exception e) {
      logger.error("[{}] Failed to remove triples from shared store", workerId, e);
      return CompletableFuture.completedFuture(
          Map.of("error", "Failed to remove triples: " + e.getMessage()));
    }
  }
}
