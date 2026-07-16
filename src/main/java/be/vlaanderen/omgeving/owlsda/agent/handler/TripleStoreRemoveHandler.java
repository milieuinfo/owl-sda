package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.HashMap;
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
        Remove RDF triples from the SHARED triple store - this store is written to by ALL workers
        in the pool, not just you. A subject URI is REQUIRED so removal is always scoped to one
        entity; predicate and/or object narrow it further within that subject. There is no
        wildcard-subject or store-wide removal: you cannot pass a bare predicate/object to sweep
        every subject that uses it, because that would delete other workers' unrelated data too.

        IMPORTANT:
        - The store is shared across all workers - only remove subjects YOU are responsible for
          fixing, never another worker's target class/shapes
        - Blank nodes linked to removed triples are automatically cleaned up
        - Orphaned blank nodes (no longer referenced by named resources) are removed recursively
        - Use triplestore_read to verify what exists before removing

        Examples:
        - Remove all triples about a subject: {subject: "http://example.org/Person1"}
        - Remove specific property: {subject: "http://example.org/Person1", predicate: "http://example.org/age"}
        - Remove specific value: {subject: "http://example.org/Person1", predicate: "http://example.org/age", object: "30"}
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
                        "type",
                        "string",
                        "description",
                        "Subject URI to match (REQUIRED - removal cannot be unscoped since the"
                            + " store is shared across workers)"),
                "predicate",
                    Map.of(
                        "type", "string",
                        "description", "Predicate URI to match (optional, null = wildcard)"),
                "object",
                    Map.of(
                        "type", "string",
                        "description",
                            "Object URI or literal to match (optional, null = wildcard)")),
        "required", List.of("subject"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String subject = (String) arguments.get("subject");
    String predicate = (String) arguments.get("predicate");
    String object = (String) arguments.get("object");

    if (subject == null || subject.isBlank()) {
      return errorResult(
          "A subject URI is required: this triple store is SHARED across all workers, so"
              + " removing by predicate/object alone (or with no pattern at all) would delete"
              + " other workers' data too. Scope this call to the one subject you are fixing.");
    }

    try {
      int triplesRemoved =
          tripleStore.removeTriplesWithBlankNodes(subject, predicate, object, workerId);
      long totalTriples = tripleStore.size();

      String message =
          String.format(
              "Removed %d triples (including any orphaned blank nodes). Shared store now contains %d triples.",
              triplesRemoved, totalTriples);

      Map<String, Object> response = new HashMap<>();
      response.put("status", "success");
      response.put("triples_removed", triplesRemoved);
      response.put("total_triples", totalTriples);

      if (triplesRemoved == 0) {
        response.put(
            "warning",
            "⚠️ No triples matched this pattern, so nothing was removed. Common causes: the"
                + " subject/predicate/object doesn't exactly match what's stored (check exact"
                + " casing and full URI), or a literal object needs its datatype/language tag"
                + " (e.g. \"2023-01-01\"^^xsd:date) to match. Use triplestore_read to see the"
                + " exact triples before retrying.");
        response.put("message", message + " (no matching triples found)");
      } else {
        response.put("message", message);
      }

      return CompletableFuture.completedFuture(response);
    } catch (Exception e) {
      logger.error("[{}] Failed to remove triples from shared store", workerId, e);
      return errorResult("Failed to remove triples: " + e.getMessage());
    }
  }
}
