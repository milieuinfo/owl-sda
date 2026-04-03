package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether delegated shapes produced concrete RDF instances in the shared output model
 * and do not have remaining class-related SHACL violations.
 */
class ShapeCompletionEvaluator {

  private static final Logger logger = LoggerFactory.getLogger(ShapeCompletionEvaluator.class);

  private final WorkerTripleStore sharedTripleStore;
  private final Shacl shacl;

  ShapeCompletionEvaluator(WorkerTripleStore sharedTripleStore, Shacl shacl) {
    this.sharedTripleStore = sharedTripleStore;
    this.shacl = shacl;
  }

  CompletionBatch evaluate(List<Shacl.Shape> unprocessedShapes, int shapesToEvaluate) {
    List<Shacl.Shape> completedShapes = new ArrayList<>();
    List<String> completedShapeNames = new ArrayList<>();
    List<String> skippedShapeNames = new ArrayList<>();
    List<String> skippedByValidationShapeNames = new ArrayList<>();

    Model model = getCurrentModel();
    ValidationReport validationReport = (model != null && shacl != null) ? shacl.validate(model) : null;

    for (int i = 0; i < shapesToEvaluate; i++) {
      Shacl.Shape shape = unprocessedShapes.get(i);
      CompletionStatus completionStatus = evaluateShapeCompletion(shape, model, validationReport);
      switch (completionStatus) {
        case COMPLETE -> {
          completedShapes.add(shape);
          completedShapeNames.add(shape.getName());
        }
        case SKIPPED_VALIDATION -> skippedByValidationShapeNames.add(shape.getName());
        case SKIPPED_NO_INSTANCES -> skippedShapeNames.add(shape.getName());
      }
    }

    return new CompletionBatch(completedShapes, completedShapeNames, skippedShapeNames,
        skippedByValidationShapeNames);
  }

  private CompletionStatus evaluateShapeCompletion(Shacl.Shape shape, Model model,
      ValidationReport validationReport) {
    if (model == null || model.isEmpty()) {
      logger.warn("Shape '{}': Triple store is null or empty (size={})",
          shape.getName(), sharedTripleStore != null ? sharedTripleStore.size() : 0);
      return CompletionStatus.SKIPPED_NO_INSTANCES;
    }

    String targetClassUri = ShapeValidationMatcher.getTargetClassUri(shape).orElse(null);
    if (targetClassUri == null) {
      logger.warn("Could not extract target class URI from shape '{}'", shape.getName());
      return CompletionStatus.SKIPPED_NO_INSTANCES;
    }

    try {
      Property rdfType = RDF.type;
      Resource targetClass = model.createResource(targetClassUri);

      logger.info("Checking shape '{}' for instances of target class URI: '{}'",
          shape.getName(), targetClassUri);

      boolean hasInstances = ShapeValidationMatcher.hasInstancesForTargetClass(
          model, shacl != null ? shacl.getOntology() : null, rdfType, targetClass);

      if (!hasInstances) {
        logger.warn("Shape '{}': No instances found for target class '{}' (store size: {} triples)",
            shape.getName(), targetClassUri, model.size());

        if (logger.isDebugEnabled()) {
          Map<String, Integer> typeCounts = new HashMap<>();
          model.listStatements(null, rdfType, (Resource) null).forEachRemaining(stmt -> {
            String type = stmt.getObject().toString();
            typeCounts.merge(type, 1, Integer::sum);
          });
          typeCounts.forEach((type, count) -> logger.debug("  {} : {} instances", type, count));
          logger.debug("Total rdf:type statements found: {}",
              typeCounts.values().stream().mapToInt(Integer::intValue).sum());
        }

        return CompletionStatus.SKIPPED_NO_INSTANCES;
      }

      if (validationReport != null && ShapeValidationMatcher.hasViolationsForTargetClass(
          model, shacl != null ? shacl.getOntology() : null, validationReport, targetClass)) {
        logger.warn("Shape '{}': Instances exist but class-related validation violations remain for '{}'",
            shape.getName(), targetClassUri);
        return CompletionStatus.SKIPPED_VALIDATION;
      }

      logger.info("Shape '{}': complete (instances present and no class-related violations)",
          shape.getName());
      return CompletionStatus.COMPLETE;
    } catch (Exception e) {
      logger.error("Error checking completion for shape '{}': {}", shape.getName(),
          e.getMessage(), e);
      return CompletionStatus.SKIPPED_NO_INSTANCES;
    }
  }

  private Model getCurrentModel() {
    if (sharedTripleStore == null || sharedTripleStore.size() == 0) {
      return null;
    }
    return sharedTripleStore.getModel();
  }

  private enum CompletionStatus {
    COMPLETE,
    SKIPPED_NO_INSTANCES,
    SKIPPED_VALIDATION
  }

  record CompletionBatch(List<Shacl.Shape> completedShapes, List<String> completedShapeNames,
                         List<String> skippedShapeNames,
                         List<String> skippedByValidationShapeNames) {
  }
}
