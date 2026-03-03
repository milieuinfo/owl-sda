package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.benchmark.DefaultBenchmarkSnapshotData;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates worker batches and supervisor-led output editing.
 */
public record SupervisorWorkflow(Config config, Shacl shacl,
                                 Supervisor supervisor,
                                 SupervisorReviewCoordinator reviewCoordinator,
                                 BenchmarkService benchmarkService,
                                 WorkerTripleStore sharedTripleStore,
                                 SessionPool workerSessionPool) {

  private static final Logger logger = LoggerFactory.getLogger(SupervisorWorkflow.class);
  private static final int MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS = 3;

  /**
   * Collects worker sessions from the pool for snapshotting delegation instructions.
   */
  private List<Session> getWorkerSessions() {
    List<Session> sessions = new ArrayList<>();
    if (workerSessionPool != null) {
      // Access the allSessions field from SessionPool to get all worker sessions
      // This includes both available and currently-in-use sessions
      try {
        // Use reflection to access the private allSessions list if needed,
        // or if there's a public getter, use that instead
        sessions.addAll(workerSessionPool.getAllSessions());
      } catch (Exception e) {
        logger.warn("Failed to get worker sessions: {}", e.getMessage());
      }
    }
    return sessions;
  }

  private Session getSupervisorSession() {
    return supervisor != null ? supervisor.supervisorSession() : null;
  }

  private Session getReviewerSession() {
    return reviewCoordinator != null ? reviewCoordinator.getReviewerSession() : null;
  }

  public void run(int shapesPerBatch) {
    if (shacl == null || shacl.getShapes() == null) {
      logger.error("Cannot run workflow: SHACL or shapes are null");
      return;
    }

    int totalShapes = shacl.getShapes().size();
    int shapes = shapesPerBatch;
    int poolCount = (config != null) ? config.getPoolCount() : 1;
    int batchSize = (config != null) ? config.getBatchSize() : 1;
    boolean firstPass = true;
    int consecutiveEmptyDelegationRounds = 0;

    // Phase 1: Delegate batches of shapes to workers for processing
    while (shapes <= totalShapes) {
      long batchStart = System.currentTimeMillis();
      try {
        // Supervisor delegates batch to concurrent workers
        boolean success = supervisor.orchestrate(shapes, firstPass);
        firstPass = false;

        long durationMs = System.currentTimeMillis() - batchStart;
        if (benchmarkService != null && benchmarkService.isEnabled()) {
          List<Session> workerSessions = getWorkerSessions();
          int currentViolations = getCurrentOutputViolations();
          int completedShapes = getCompletedShapesCount();
          benchmarkService.createBatchSnapshot(
              new DefaultBenchmarkSnapshotData(
                  "GENERATE",
                  completedShapes,
                  durationMs,
                  getSupervisorSession(),
                  getReviewerSession(),
                  sharedTripleStore,
                  workerSessions
              ),
              currentViolations
          );
        }

        if (success) {
          consecutiveEmptyDelegationRounds = 0;

          // Check if any shapes have successfully passed (processed with no related violations)
          int shapesWithNoViolations = countShapesWithNoViolations();

          if (shapesWithNoViolations > 0 && shapes < totalShapes) {
            // Add new shapes to process since some have successfully passed
            int newShapesTarget = Math.min(shapes + (batchSize * poolCount), totalShapes);
            logger.info("Adding new shapes to process: {} shapes passed validation, expanding from {} to {} shapes",
                shapesWithNoViolations, shapes, newShapesTarget);
            shapes = newShapesTarget;
          } else if (shapes >= totalShapes) {
            // All shapes have been delegated, exit loop
            logger.info("All {} shapes have been delegated for processing", totalShapes);
            break;
          }
        } else {
          int delegatedWorkers = supervisor.getDelegatedWorkerCount();
          if (delegatedWorkers == 0) {
            consecutiveEmptyDelegationRounds++;
            logger.error(
                "Supervisor delegated to 0 workers in round {}. consecutive empty delegation rounds: {}/{}",
                shapes,
                consecutiveEmptyDelegationRounds,
                MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS
            );

            if (consecutiveEmptyDelegationRounds >= MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS) {
              logger.error(
                  "Stopping workflow to avoid infinite loop: supervisor repeatedly produced no worker delegations"
              );
              return;
            }
          } else {
            consecutiveEmptyDelegationRounds = 0;
            logger.error("Batch processing failed after delegating to {} worker(s), retrying batch with {} shapes",
                delegatedWorkers, shapes);
          }
        }
      } catch (Exception e) {
        logger.error("Workflow error during batch processing: {}", e.getMessage(), e);
        return;
      }
    }

    // Phase 2: All shapes processed - finalize output
    finalizeWorkflow();
  }

  private int getCurrentOutputViolations() {
    if (shacl == null) {
      return -1;
    }

    // During generation, the shared store is the live source of truth.
    if (sharedTripleStore != null && sharedTripleStore.size() > 0) {
      try {
        return countViolations(sharedTripleStore.getModel());
      } catch (Exception e) {
        logger.debug("Could not calculate shared-triple-store violations for benchmark snapshot: {}", e.getMessage());
      }
    }

    if (config != null && config.getOutputPath() != null) {
      Path outputFile = Path.of(config.getOutputPath());
      if (Files.exists(outputFile)) {
        try {
          String turtleData = Files.readString(outputFile);
          Model dataModel = ModelFactory.createDefaultModel();
          dataModel.read(new StringReader(turtleData), null, "TURTLE");
          return countViolations(dataModel);
        } catch (Exception e) {
          logger.debug("Could not calculate output-file violations for benchmark snapshot: {}", e.getMessage());
        }
      }
    }

    return -1;
  }

  private int countViolations(Model dataModel) {
    ValidationReport report = shacl.validate(dataModel);
    return report.getEntries().size();
  }

  /**
   * Gets the count of shapes that have been marked as processed.
   */
  private int getCompletedShapesCount() {
    if (shacl == null || shacl.getShapes() == null) {
      return 0;
    }
    return (int) shacl.getShapes().stream()
        .filter(Shacl.Shape::isProcessed)
        .count();
  }

  /**
   * Counts how many processed shapes have no related validation violations.
   * A shape is considered to have "no violations" if it has been marked as processed
   * and there are no validation report entries targeting instances of that shape's target class.
   */
  private int countShapesWithNoViolations() {
    if (shacl == null || shacl.getShapes() == null) {
      return 0;
    }

    // Get current validation report
    Model dataModel = getCurrentDataModel();
    if (dataModel == null) {
      return 0;
    }

    ValidationReport report = shacl.validate(dataModel);

    // Build set of shape URIs that have violations
    Set<String> shapesWithViolations = new HashSet<>();
    report.getEntries().forEach(entry -> {
      if (entry.source() != null) {
        shapesWithViolations.add(entry.source().toString());
      }
    });

    // Count processed shapes that have no violations
    int count = 0;
    for (Shacl.Shape shape : shacl.getShapes()) {
      if (shape.isProcessed()) {
        // Get the shape URI from the shape's model
        String shapeUri = getShapeUri(shape);
        if (shapeUri != null && !shapesWithViolations.contains(shapeUri)) {
          count++;
        }
      }
    }

    return count;
  }

  /**
   * Gets the current data model from either the shared triple store or output file.
   */
  private Model getCurrentDataModel() {
    // During generation, the shared store is the live source of truth
    if (sharedTripleStore != null && sharedTripleStore.size() > 0) {
      try {
        return sharedTripleStore.getModel();
      } catch (Exception e) {
        logger.debug("Could not get shared triple store model: {}", e.getMessage());
      }
    }

    // Fall back to output file
    if (config != null && config.getOutputPath() != null) {
      Path outputFile = Path.of(config.getOutputPath());
      if (Files.exists(outputFile)) {
        try {
          String turtleData = Files.readString(outputFile);
          Model dataModel = ModelFactory.createDefaultModel();
          dataModel.read(new StringReader(turtleData), null, "TURTLE");
          return dataModel;
        } catch (Exception e) {
          logger.debug("Could not read output file model: {}", e.getMessage());
        }
      }
    }

    return null;
  }

  /**
   * Extracts the shape URI from a Shape object.
   */
  private String getShapeUri(Shacl.Shape shape) {
    try {
      Model shapeModel = shape.getModel();
      if (shapeModel != null) {
        Resource nodeShape = shapeModel.createResource("http://www.w3.org/ns/shacl#NodeShape");
        Resource shapeResource = shapeModel.listResourcesWithProperty(
            org.apache.jena.vocabulary.RDF.type, nodeShape
        ).nextResource();
        if (shapeResource != null) {
          return shapeResource.getURI();
        }
      }
    } catch (Exception e) {
      logger.debug("Could not extract shape URI for shape {}: {}", shape.getName(), e.getMessage());
    }
    return null;
  }

  private void finalizeWorkflow() {
    try {
      int poolCount = (config != null) ? config.getPoolCount() : 1;

      // FINALIZING stage
      if (poolCount > 1 && supervisor != null) {
        logger.info("All shapes processed - Supervisor directly editing output for consistency");
        long finalizationStart = System.currentTimeMillis();
        supervisor.finalizeOutput();
        long finalizationDuration = System.currentTimeMillis() - finalizationStart;

        // Create FINALIZING benchmark
        if (benchmarkService != null && benchmarkService.isEnabled()) {
          int currentViolations = getCurrentOutputViolations();
          int completedShapes = getCompletedShapesCount();
          benchmarkService.createBatchSnapshot(
              new DefaultBenchmarkSnapshotData(
                  "FINALIZING",
                  completedShapes,
                  finalizationDuration,
                  getSupervisorSession(),
                  getReviewerSession(),
                  sharedTripleStore,
                  getWorkerSessions()
              ),
              currentViolations
          );
        }
      }

      // REVIEW stage
      if (reviewCoordinator != null) {
        logger.info("Running final review on finalized output");
        long reviewStart = System.currentTimeMillis();
        reviewCoordinator.review();
        long reviewDuration = System.currentTimeMillis() - reviewStart;

        // Create REVIEW benchmark
        if (benchmarkService != null && benchmarkService.isEnabled()) {
          int currentViolations = getCurrentOutputViolations();
          int completedShapes = getCompletedShapesCount();
          benchmarkService.createBatchSnapshot(
              new DefaultBenchmarkSnapshotData(
                  "REVIEW",
                  completedShapes,
                  reviewDuration,
                  getSupervisorSession(),
                  getReviewerSession(),
                  sharedTripleStore,
                  getWorkerSessions()
              ),
              currentViolations
          );
        }
      }
    } catch (Exception e) {
      logger.error("Finalization error: {}", e.getMessage(), e);
      // Retry
      finalizeWorkflow();
    }
  }
}
