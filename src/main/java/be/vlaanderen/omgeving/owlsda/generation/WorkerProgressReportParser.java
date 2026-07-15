package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses each delegated worker's structured "Worker Progress Report" context and marks the shapes
 * it claims to have completed as processed, once its status/validation_result fields indicate real
 * conforming progress (not a BLOCKED report).
 */
class WorkerProgressReportParser {

  private static final Logger logger = LoggerFactory.getLogger(WorkerProgressReportParser.class);
  private static final String WORKER_PROGRESS_CONTEXT_NAME = WorkerProgressHandler.CONTEXT_NAME;

  private final Shacl shacl;
  private final ShapeProcessingTracker shapeProcessingTracker;

  WorkerProgressReportParser(Shacl shacl, ShapeProcessingTracker shapeProcessingTracker) {
    this.shacl = shacl;
    this.shapeProcessingTracker = shapeProcessingTracker;
  }

  int markCompletedShapesFromWorkerProgress(List<Session> delegatedWorkerSessions) {
    if (delegatedWorkerSessions == null
        || delegatedWorkerSessions.isEmpty()
        || shacl == null
        || shacl.getShapes() == null
        || shacl.getShapes().isEmpty()) {
      return 0;
    }

    Set<String> completedShapeNames = new HashSet<>();
    for (Session workerSession : delegatedWorkerSessions) {
      if (workerSession == null) {
        continue;
      }

      String workerReport =
          SessionContextLookup.findContent(workerSession, WORKER_PROGRESS_CONTEXT_NAME);
      if (workerReport == null || workerReport.isBlank()) {
        continue;
      }

      Map<String, String> progress = parseProgressReport(workerReport);
      if (!isConformingProgress(progress)) {
        continue;
      }

      String targetShape = progress.getOrDefault("target_shape", "").trim();
      if (targetShape.isBlank()) {
        continue;
      }

      for (String shapeName : splitShapeNames(targetShape)) {
        if (shapeName.isBlank()) {
          continue;
        }

        boolean exists =
            shacl.getShapes().stream().anyMatch(shape -> shapeName.equals(shape.getName()));
        if (exists) {
          completedShapeNames.add(shapeName);
        }
      }
    }

    if (completedShapeNames.isEmpty()) {
      return 0;
    }

    shacl.getShapes().stream()
        .filter(shape -> completedShapeNames.contains(shape.getName()))
        .forEach(shape -> shape.setProcessed(true));

    if (shapeProcessingTracker != null) {
      shapeProcessingTracker.markCompleted(new ArrayList<>(completedShapeNames));
    }

    logger.info(
        "Marked {} shape(s) as processed from worker progress: {}",
        completedShapeNames.size(),
        String.join(", ", completedShapeNames));
    return completedShapeNames.size();
  }

  private Map<String, String> parseProgressReport(String report) {
    Map<String, String> values = new HashMap<>();
    if (report == null || report.isBlank()) {
      return values;
    }

    String[] lines = report.split("\\R");
    for (String line : lines) {
      if (line == null || line.isBlank()) {
        continue;
      }

      int separator = line.indexOf('=');
      if (separator <= 0 || separator >= line.length() - 1) {
        continue;
      }

      String key = line.substring(0, separator).trim().toLowerCase();
      String value = line.substring(separator + 1).trim();
      if (!key.isBlank() && !value.isBlank()) {
        values.put(key, value);
      }
    }

    return values;
  }

  private boolean isConformingProgress(Map<String, String> progress) {
    if (progress == null || progress.isEmpty()) {
      return false;
    }

    String status = progress.getOrDefault("status", "").trim();
    String validationResult = progress.getOrDefault("validation_result", "").trim();

    return isAcceptableWorkerStatus(status) && isConformingValidationResult(validationResult);
  }

  /**
   * A worker report only counts as progress if it reflects new/verified work (not {@code BLOCKED}).
   * Derived from {@link WorkerProgressHandler.WorkerStatus}, the tool contract workers actually
   * submit, so this can't silently drift from the values the LLM is instructed to send.
   */
  private static boolean isAcceptableWorkerStatus(String status) {
    try {
      WorkerProgressHandler.WorkerStatus parsed =
          WorkerProgressHandler.WorkerStatus.valueOf(status);
      return parsed == WorkerProgressHandler.WorkerStatus.CREATED
          || parsed == WorkerProgressHandler.WorkerStatus.FIXED
          || parsed == WorkerProgressHandler.WorkerStatus.VERIFIED_NO_CHANGE;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isConformingValidationResult(String validationResult) {
    return WorkerProgressHandler.ValidationResult.CONFORMS.name().equals(validationResult);
  }

  private List<String> splitShapeNames(String targetShapeField) {
    if (targetShapeField == null || targetShapeField.isBlank()) {
      return List.of();
    }

    String[] tokens = targetShapeField.split("[;,]");
    List<String> shapeNames = new ArrayList<>();
    for (String token : tokens) {
      String trimmed = token.trim();
      if (!trimmed.isBlank()) {
        shapeNames.add(trimmed);
      }
    }

    if (shapeNames.isEmpty()) {
      return List.of(targetShapeField.trim());
    }

    return shapeNames;
  }
}
