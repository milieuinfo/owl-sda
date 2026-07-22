package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.rdf.model.Model;

/** Formats the supervisor's delegation-planning "which shapes go to which worker" prompt text. */
final class ShapeDistributionFormatter {

  private static final int MAX_SHAPE_NAMES_PER_WORKER_IN_DISTRIBUTION = 4;

  private ShapeDistributionFormatter() {}

  /**
   * Calculates optimal shape distribution across all workers to maximize parallelism. Each worker
   * gets exactly batch_size shapes (configured value). Total shapes assigned = batch_size *
   * worker_count.
   */
  static String format(Shacl shacl, ConcurrentWorkerBatch concurrentWorkerBatch, int totalShapes) {
    int workerCount = concurrentWorkerBatch.getWorkerCount();
    if (workerCount <= 0) {
      return "";
    }

    List<Shacl.Shape> unprocessedShapes = unprocessedShapes(shacl);
    if (unprocessedShapes.isEmpty()) {
      return "";
    }

    int batchSize =
        (concurrentWorkerBatch.config() != null)
            ? concurrentWorkerBatch.config().getBatchSize()
            : 1;

    int actualShapesToAssign =
        ConcurrentWorkerBatch.totalAssignableShapes(totalShapes, unprocessedShapes.size());

    StringBuilder distribution = new StringBuilder();
    distribution
        .append("OPTIMIZED SHAPE DISTRIBUTION (batch-size=")
        .append(batchSize)
        .append(", ")
        .append(workerCount)
        .append(" workers, ")
        .append(actualShapesToAssign)
        .append(" total shapes):\n");

    for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
      int[] range =
          ConcurrentWorkerBatch.workerShapeRange(workerIndex, batchSize, actualShapesToAssign);
      int startShape = range[0];
      int endShape = range[1];

      if (startShape < actualShapesToAssign) {
        distribution.append(
            String.format("POOL-%d: shapes [%d..%d) - ", workerIndex, startShape, endShape));

        int maxNames = Math.min(endShape, startShape + MAX_SHAPE_NAMES_PER_WORKER_IN_DISTRIBUTION);
        List<String> sampleNames = new ArrayList<>();
        for (int i = startShape; i < maxNames; i++) {
          sampleNames.add(
              describeShapeAndTargetClass(unprocessedShapes.get(i), shacl.getOntology()));
        }
        distribution.append(String.join(", ", sampleNames));

        int omitted = endShape - maxNames;
        if (omitted > 0) {
          distribution.append(" ... (+").append(omitted).append(" more)");
        }
        distribution.append("\n");
      }
    }

    distribution.append("\nDelegate immediately using these assignments.\n");
    distribution.append(
        "NOTE: a shape's name is the validation rule's name, not necessarily a class name - never"
            + " assume it is one. Tell each worker to type instances with the class shown in"
            + " parentheses after each shape above, not with the shape's own name.\n");
    distribution.append(
        "NOTE: where a class above is shown with '[IRI template: ...]', that literal RFC 6570"
            + " template was resolved directly from the ontology's hydra:search declaration for"
            + " that class - copy it verbatim into the worker's assignment and instruct them to"
            + " substitute real values for each {variable}. Do not paraphrase or shorten it, and do"
            + " not invent a different URI scheme for that class. A class with no template shown"
            + " has no hydra:search declaration; for those, restate the ontology's default prefix"
            + " and a consistent local-name convention instead.\n");
    return distribution.toString();
  }

  private static List<Shacl.Shape> unprocessedShapes(Shacl shacl) {
    if (shacl == null || shacl.getShapes() == null) {
      return List.of();
    }
    return shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).toList();
  }

  /**
   * Renders a shape for the supervisor's delegation-planning text as {@code ShapeName (class:
   * LocalClassName) [IRI template: ...]}. The class name is shown alongside the shape because the
   * supervisor otherwise only ever sees the shape's own name and has no way to tell workers what
   * the real target class is - workers then reliably parrot the shape name back as the class to
   * instantiate. The IRI template (when the ontology declares one via {@code hydra:search}) is
   * resolved here in code rather than left for the LLM to find, since models routinely missed it
   * buried inside a large ontology dump and fell back to inventing their own URI scheme.
   */
  private static String describeShapeAndTargetClass(Shacl.Shape shape, Model ontologyModel) {
    String targetClassUri = ShapeValidationMatcher.getTargetClassUri(shape).orElse(null);
    if (targetClassUri == null) {
      return shape.getName();
    }
    String base = shape.getName() + " (class: " + localNameOf(targetClassUri) + ")";
    return HydraIriTemplateResolver.resolveTemplate(ontologyModel, targetClassUri)
        .map(template -> base + " [IRI template: " + template + "]")
        .orElse(base);
  }

  private static String localNameOf(String uri) {
    int hashIndex = uri.lastIndexOf('#');
    int slashIndex = uri.lastIndexOf('/');
    int splitIndex = Math.max(hashIndex, slashIndex);
    return splitIndex >= 0 && splitIndex < uri.length() - 1 ? uri.substring(splitIndex + 1) : uri;
  }
}
