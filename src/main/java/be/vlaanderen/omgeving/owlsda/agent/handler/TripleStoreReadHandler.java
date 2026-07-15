package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for reading/querying triples from the shared triple store. Supports pattern-based queries
 * and full store export.
 */
public record TripleStoreReadHandler(WorkerTripleStore tripleStore, String workerId)
    implements SessionHandler {

  public static final String NAME = "triplestore_read";

  private static final Logger logger = LoggerFactory.getLogger(TripleStoreReadHandler.class);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Read/query RDF triples from the SHARED triple store.
        All workers can see all triples in the shared store.

        Modes:
        - 'all': Get all triples as TURTLE (default)
        - 'query': Query triples by pattern (subject/predicate/object)
        - 'count': Get the number of triples in the shared store

        IMPORTANT:
        - The store is shared across all workers
        - You can see triples added by other workers
        - Use this to coordinate and avoid duplicates

        Query examples:
        - All triples: {mode: "all"}
        - Triple count: {mode: "count"}
        - Find by subject: {mode: "query", subject: "http://example.org/Person1"}
        - Find by predicate: {mode: "query", predicate: "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"}
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "mode",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("all", "query", "count"),
                        "description",
                        "Operation mode: 'all' (get all triples), 'query' (pattern search), 'count' (count triples)"),
                "subject",
                    Map.of(
                        "type", "string",
                        "description", "Subject URI to match in query mode (optional)"),
                "predicate",
                    Map.of(
                        "type", "string",
                        "description", "Predicate URI to match in query mode (optional)"),
                "object",
                    Map.of(
                        "type", "string",
                        "description", "Object URI or literal to match in query mode (optional)")),
        "required", List.of());
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String mode = (String) arguments.getOrDefault("mode", "all");

    try {
      return switch (mode) {
        case "count" -> handleCount();
        case "query" -> handleQuery(arguments);
        case "all" -> handleAll();
        default -> errorResult("Unknown mode: " + mode + ". Use 'all', 'query', or 'count'.");
      };
    } catch (Exception e) {
      logger.error("[{}] Failed to read from shared triple store", workerId, e);
      return errorResult("Failed to read triples: " + e.getMessage());
    }
  }

  private CompletableFuture<Object> handleCount() {
    long count = tripleStore.size();
    return CompletableFuture.completedFuture(
        Map.of(
            "status",
            "success",
            "count",
            count,
            "message",
            String.format("The shared triple store contains %d triples.", count)));
  }

  private CompletableFuture<Object> handleQuery(Map<String, Object> arguments) {
    String subject = (String) arguments.get("subject");
    String predicate = (String) arguments.get("predicate");
    String object = (String) arguments.get("object");

    List<String> results = tripleStore.queryTriples(subject, predicate, object, workerId);

    return CompletableFuture.completedFuture(
        Map.of(
            "status",
            "success",
            "count",
            results.size(),
            "triples",
            results,
            "message",
            String.format("Query returned %d triples from the shared store.", results.size())));
  }

  private CompletableFuture<Object> handleAll() {
    long count = tripleStore.size();

    if (count == 0) {
      return CompletableFuture.completedFuture(
          Map.of(
              "status", "success",
              "count", 0,
              "data", "",
              "message", "The shared triple store is empty."));
    }

    String allTriples = tripleStore.getAllTriples();

    return CompletableFuture.completedFuture(
        Map.of(
            "status",
            "success",
            "count",
            count,
            "data",
            allTriples,
            "message",
            String.format("Retrieved all %d triples from the shared store.", count)));
  }
}
