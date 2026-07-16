package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore.AddResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.jena.rdf.model.RDFNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for adding triples to the shared triple store. Workers use this to collaboratively build
 * the output. Detects and reports duplicate triples to help workers coordinate.
 */
public record TripleStoreAddHandler(WorkerTripleStore tripleStore, String workerId)
    implements SessionHandler {

  public static final String NAME = "triplestore_add";

  private static final Logger logger = LoggerFactory.getLogger(TripleStoreAddHandler.class);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Add RDF triples to the SHARED triple store.
        All workers contribute to the same store, enabling collaboration.

        SYNTAX: `data` must be a complete, self-contained Turtle document - every call is parsed on
        its own, so prefixes declared in an earlier call are NOT remembered. Well-known prefixes
        used anywhere in the ontology (rdf, rdfs, owl, dc, xsd, prov, skos, sosa, ssn, ssn-system,
        ...) are auto-declared for you if you omit them, but any prefix you invent yourself still
        needs its own `@prefix` line. If a call is rejected, the error names the exact problem
        (undefined prefix, malformed IRI, wrong term position); fix that one thing and resubmit -
        don't guess at a different rewrite.

        IMPORTANT:
        - The store is shared across all workers
        - Duplicate triples will be detected and reported
        - You'll receive a warning if you try to add triples that already exist
        - Use triplestore_read to check existing triples before adding
        - Coordinate with other workers to avoid conflicts

        The Supervisor will merge the shared store to the output file when all shapes are processed.
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "data",
                Map.of(
                    "type", "string",
                    "description", "TURTLE formatted RDF data to add to the shared triple store")),
        "required", List.of("data"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String data = (String) arguments.get("data");

    if (data == null || data.trim().isEmpty()) {
      return errorResult("No data provided");
    }

    try {
      AddResult result = tripleStore.addTriples(data, workerId);

      if (result.hasError()) {
        return errorResult(result.getError());
      }

      // Build response with duplicate information
      Map<String, Object> response = new HashMap<>();
      response.put("status", "success");
      response.put("triples_added", result.getTriplesAdded());
      response.put("total_triples", result.getTotalTriples());

      if (result.hasDuplicates()) {
        List<String> duplicateStrings =
            result.getDuplicates().stream()
                .map(
                    stmt ->
                        String.format(
                            "<%s> <%s> %s",
                            stmt.getSubject().getURI(),
                            stmt.getPredicate().getURI(),
                            formatObject(stmt.getObject())))
                .collect(Collectors.toList());

        response.put("duplicates_ignored", result.getDuplicates().size());
        response.put("duplicate_triples", duplicateStrings);
        response.put(
            "warning",
            String.format(
                "⚠️ %d duplicate triples were ignored (already exist in shared store). "
                    + "Another worker may have already added them. Check triplestore_read to see existing data.",
                result.getDuplicates().size()));
        response.put(
            "message",
            String.format(
                "Added %d new triples, ignored %d duplicates. Shared store now contains %d triples.",
                result.getTriplesAdded(), result.getDuplicates().size(), result.getTotalTriples()));
      } else {
        response.put(
            "message",
            String.format(
                "Added %d triples. Shared store now contains %d triples.",
                result.getTriplesAdded(), result.getTotalTriples()));
      }

      return CompletableFuture.completedFuture(response);
    } catch (Exception e) {
      logger.error("[{}] Failed to add triples to shared store", workerId, e);
      return errorResult("Failed to add triples: " + e.getMessage());
    }
  }

  private String formatObject(RDFNode node) {
    if (node.isResource()) {
      return "<" + node.asResource().getURI() + ">";
    } else if (node.isLiteral()) {
      return "\"" + node.asLiteral().getString() + "\"";
    }
    return node.toString();
  }
}
