package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.benchmark.DefaultBenchmarkSnapshotData;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates worker batches and supervisor-led output editing. */
public record SupervisorWorkflow(
    Config config,
    Shacl shacl,
    Supervisor supervisor,
    SupervisorReviewCoordinator reviewCoordinator,
    BenchmarkService benchmarkService,
    WorkerTripleStore sharedTripleStore,
    SessionPool workerSessionPool) {

  private static final Logger logger = LoggerFactory.getLogger(SupervisorWorkflow.class);
  private static final int MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS = 3;
  private static final int MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS = 5;
  private static final int MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS = 4;
  private static final int MAX_FINALIZATION_ATTEMPTS = 3;

  /** Collects worker sessions from the pool for snapshotting delegation instructions. */
  private List<Session> getWorkerSessions() {
    if (workerSessionPool == null) {
      return List.of();
    }
    try {
      return new ArrayList<>(workerSessionPool.getAllSessions());
    } catch (Exception e) {
      logger.warn("Failed to get worker sessions: {}", e.getMessage());
      return List.of();
    }
  }

  private Session getSupervisorSession() {
    return supervisor != null ? supervisor.supervisorSession() : null;
  }

  private Session getReviewerSession() {
    return reviewCoordinator != null ? reviewCoordinator.getReviewerSessionIfInitialized() : null;
  }

  public void run(int shapesPerBatch) {
    if (!hasValidShaclShapes()) {
      logger.error("Cannot run workflow: SHACL or shapes are null");
      return;
    }

    int totalShapes = shacl.getShapes().size();
    int poolCount = (config != null) ? config.getPoolCount() : 1;
    int batchSize = (config != null) ? config.getBatchSize() : 1;

    runGenerationPhase(shapesPerBatch, totalShapes, poolCount, batchSize);
  }

  private boolean hasValidShaclShapes() {
    return shacl != null && shacl.getShapes() != null;
  }

  /**
   * Drives the round-by-round delegation loop until every shape is processed and validation is
   * clean, then hands off to finalization and review. Each round delegates {@code shapes} shapes
   * (growing via {@link #calculateNextShapesTarget} as earlier shapes pass validation) and then
   * checks progress against three independent stall detectors, each an escape hatch against a
   * different way a round can fail to make progress:
   *
   * <ul>
   *   <li>{@code consecutiveEmptyDelegationRounds} (vs {@link
   *       #MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS}): the supervisor LLM delegated to zero workers
   *       this round. Tracked in {@link #shouldAbortAfterFailedRound}.
   *   <li>{@code consecutiveNoProgressRounds} (vs {@link #MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS}):
   *       workers ran, but neither the completed-shape count nor the violation count improved.
   *   <li>{@code consecutiveStalledViolationRounds} (vs {@link
   *       #MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS}): violations exist but {@code
   *       lastViolationSignature} (a hash of the current violation set) hasn't changed, meaning
   *       repair attempts aren't touching the actual failing triples.
   * </ul>
   *
   * {@code lastProcessedShapes} and {@code lastViolations} hold the previous round's counts so each
   * check is a delta, not an absolute; all three counters reset to zero the moment their condition
   * improves, so only *consecutive* stalls count against the threshold. Hitting any threshold
   * aborts the run rather than looping indefinitely against an LLM that isn't making progress.
   */
  private void runGenerationPhase(
      int shapesPerBatch, int totalShapes, int poolCount, int batchSize) {
    // Clamp to totalShapes: a configured batch (batch-size * pool-count) larger than the shape
    // count must still run one (final) round covering everything, rather than making the initial
    // "shapes <= totalShapes" loop guard false and silently skipping generation entirely.
    int shapes = Math.min(shapesPerBatch, totalShapes);
    boolean firstPass = true;
    int consecutiveEmptyDelegationRounds = 0;
    int consecutiveNoProgressRounds = 0;
    int lastProcessedShapes = 0;
    int lastViolations = Integer.MAX_VALUE;
    int round = 0;
    int consecutiveStalledViolationRounds = 0;
    String lastViolationSignature = null;

    while (shapes <= totalShapes) {
      round++;
      long batchStart = System.currentTimeMillis();
      try {
        boolean success = supervisor.orchestrate(shapes, firstPass);
        firstPass = false;

        captureBenchmarkSnapshot("GENERATE", batchStart, round);

        if (success) {
          consecutiveEmptyDelegationRounds = 0;

          int processedShapes = getCompletedShapesCount();
          int processedDelta = processedShapes - lastProcessedShapes;
          ViolationSnapshot violationSnapshot = getCurrentViolationSnapshot();
          int currentViolations = violationSnapshot.count();
          int violationDelta =
              (lastViolations == Integer.MAX_VALUE || currentViolations < 0)
                  ? 0
                  : lastViolations - currentViolations;

          logger.info(
              "Progress: {}/{} shapes completed (delta: +{}), violations: {} (delta: {})",
              processedShapes,
              totalShapes,
              Math.max(processedDelta, 0),
              currentViolations,
              (lastViolations == Integer.MAX_VALUE ? "n/a" : String.valueOf(violationDelta)));

          // Never finalize while validation issues remain.
          if (processedShapes >= totalShapes) {
            if (currentViolations > 0) {
              int reopenedShapes = reopenShapesWithViolations();
              logger.warn(
                  "All shapes were marked processed but {} validation issue(s) remain; reopened {} violating shape(s) for delegated repair",
                  currentViolations,
                  reopenedShapes);
              if (reopenedShapes == 0) {
                logger.error(
                    "Validation issues remain but no violating shapes could be mapped; stopping to avoid infinite finalize loop");
                return;
              }
              // Keep full scope available for follow-up delegated repair rounds.
              shapes = totalShapes;
              lastProcessedShapes = getCompletedShapesCount();
              lastViolations = currentViolations;
              continue;
            }

            logger.info(
                "All {} shapes processed and validation is clean; proceeding to finalization",
                totalShapes);
            runFinalizationWithBenchmark();
            runReviewWithBenchmark();
            return;
          }

          boolean progressedByShapes = processedDelta > 0;
          boolean progressedByViolations =
              lastViolations == Integer.MAX_VALUE
                  || (currentViolations >= 0 && currentViolations < lastViolations);

          boolean sameViolationSignature =
              currentViolations > 0
                  && violationSnapshot.hasSignature()
                  && Objects.equals(violationSnapshot.signature(), lastViolationSignature);
          if (!progressedByViolations && sameViolationSignature) {
            consecutiveStalledViolationRounds++;
            logger.warn(
                "Validation stall detected: unchanged violation signature from {} for {}/{} rounds",
                violationSnapshot.source(),
                consecutiveStalledViolationRounds,
                MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS);
            if (consecutiveStalledViolationRounds >= MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS) {
              logger.error(
                  "Stopping workflow: unresolved violations are unchanged across {} consecutive rounds. Last known violation count: {}",
                  MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS,
                  currentViolations);
              return;
            }
          } else {
            consecutiveStalledViolationRounds = 0;
          }

          if (violationSnapshot.hasSignature()) {
            lastViolationSignature = violationSnapshot.signature();
          }

          if (!progressedByShapes && !progressedByViolations) {
            consecutiveNoProgressRounds++;
            logger.warn(
                "No progress in this round ({}/{} consecutive no-progress rounds)",
                consecutiveNoProgressRounds,
                MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS);

            // Log remaining unprocessed shapes for diagnostics
            logUnprocessedShapeNames(processedShapes, totalShapes);

            if (consecutiveNoProgressRounds >= MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS) {
              logger.error(
                  "Stopping workflow: {} consecutive rounds produced no progress in shapes or validation",
                  MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS);
              logger.warn(
                  "Workflow stalled with {} unprocessed shapes; attempting final delegation to all remaining shapes",
                  totalShapes - processedShapes);
              shapes = totalShapes;
              boolean finalAttempt = supervisor.orchestrate(shapes, false);
              if (finalAttempt && getCompletedShapesCount() > processedShapes) {
                logger.info("Final aggressive delegation succeeded; resuming workflow");
                consecutiveNoProgressRounds = 0;
                lastProcessedShapes = getCompletedShapesCount();
                continue;
              }
              return;
            }
          } else {
            consecutiveNoProgressRounds = 0;
            lastProcessedShapes = processedShapes;
            if (currentViolations >= 0) {
              lastViolations = currentViolations;
            }
          }

          int previousShapes = shapes;
          shapes = calculateNextShapesTarget(shapes, totalShapes, poolCount, batchSize);
          if (shapes > previousShapes) {
            logger.info("Expanding delegation scope from {} to {} shapes", previousShapes, shapes);
          }
          continue;
        }

        int delegatedWorkers = supervisor.getDelegatedWorkerCount();
        boolean shouldAbort =
            shouldAbortAfterFailedRound(shapes, delegatedWorkers, consecutiveEmptyDelegationRounds);
        if (delegatedWorkers == 0) {
          consecutiveEmptyDelegationRounds++;
        } else {
          consecutiveEmptyDelegationRounds = 0;
        }

        if (shouldAbort) {
          return;
        }
      } catch (Exception e) {
        logger.error("Workflow error during batch processing: {}", e.getMessage(), e);
        return;
      }
    }
  }

  /**
   * Reopens processed shapes that are still referenced by current validation violations. This
   * allows supervisor delegation rounds to continue repairs before finalization.
   */
  private int reopenShapesWithViolations() {
    if (shacl == null || shacl.getShapes() == null || shacl.getShapes().isEmpty()) {
      return 0;
    }

    DataModelSnapshotResolver.DataModelValidation validation = resolveCurrentDataModelValidation();
    if (validation == null) {
      return 0;
    }

    Model dataModel = validation.model();
    ValidationReport report = validation.report();

    return ShapeValidationMatcher.reopenProcessedShapesWithViolations(
            shacl.getShapes(), dataModel, shacl.getOntology(), report)
        .size();
  }

  private int calculateNextShapesTarget(
      int currentShapes, int totalShapes, int poolCount, int batchSize) {
    int shapesWithNoViolations = countShapesWithNoViolations();
    if (shapesWithNoViolations > 0 && currentShapes < totalShapes) {
      int newShapesTarget = Math.min(currentShapes + (batchSize * poolCount), totalShapes);
      logger.info(
          "Adding new shapes to process: {} shapes passed validation, expanding from {} to {} shapes",
          shapesWithNoViolations,
          currentShapes,
          newShapesTarget);
      return newShapesTarget;
    }
    return currentShapes;
  }

  private boolean shouldAbortAfterFailedRound(
      int shapes, int delegatedWorkers, int consecutiveEmptyDelegationRounds) {
    if (delegatedWorkers == 0) {
      int nextEmptyRoundCount = consecutiveEmptyDelegationRounds + 1;
      logger.error(
          "Supervisor delegated to 0 workers in round {}. consecutive empty delegation rounds: {}/{}",
          shapes,
          nextEmptyRoundCount,
          MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS);
      if (nextEmptyRoundCount >= MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS) {
        logger.error(
            "Stopping workflow to avoid infinite loop: supervisor repeatedly produced no worker delegations");
        return true;
      }
      return false;
    }

    logger.error(
        "Batch processing failed after delegating to {} worker(s), retrying batch with {} shapes",
        delegatedWorkers,
        shapes);
    return false;
  }

  private void captureBenchmarkSnapshot(String stage, long stageStart) {
    captureBenchmarkSnapshot(stage, stageStart, 0);
  }

  private void captureBenchmarkSnapshot(String stage, long stageStart, int round) {
    if (benchmarkService == null || !benchmarkService.isEnabled()) {
      return;
    }

    long durationMs = System.currentTimeMillis() - stageStart;
    int currentViolations = getCurrentOutputViolations();
    int completedShapes = getCompletedShapesCount();
    benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData(
            stage,
            round,
            completedShapes,
            durationMs,
            getSupervisorSession(),
            getReviewerSession(),
            sharedTripleStore,
            getWorkerSessions()),
        currentViolations);
  }

  private int getCurrentOutputViolations() {
    return getCurrentViolationSnapshot().count();
  }

  private ViolationSnapshot getCurrentViolationSnapshot() {
    if (shacl == null) {
      return ViolationSnapshot.unknown();
    }

    // During generation, the shared store is the live source of truth.
    if (sharedTripleStore != null && sharedTripleStore.size() > 0) {
      try {
        ValidationReport report = sharedTripleStore.getValidationSnapshot(shacl).report();
        return new ViolationSnapshot(
            report.getEntries().size(), buildViolationSignature(report), "store");
      } catch (Exception e) {
        logger.debug(
            "Could not calculate shared-triple-store violations for benchmark snapshot: {}",
            e.getMessage());
      }
    }

    if (config != null && config.getOutputPath() != null) {
      Path outputFile = Path.of(config.getOutputPath());
      if (Files.exists(outputFile)) {
        try {
          String turtleData = Files.readString(outputFile);
          Model dataModel = ModelFactory.createDefaultModel();
          dataModel.read(new StringReader(turtleData), null, "TURTLE");
          ValidationReport report = shacl.validate(dataModel);
          return new ViolationSnapshot(
              report.getEntries().size(), buildViolationSignature(report), "file");
        } catch (Exception e) {
          logger.debug(
              "Could not calculate output-file violations for benchmark snapshot: {}",
              e.getMessage());
        }
      }
    }

    return ViolationSnapshot.unknown();
  }

  private String buildViolationSignature(ValidationReport report) {
    if (report == null || report.getEntries() == null || report.getEntries().isEmpty()) {
      return "";
    }

    List<String> focusNodes =
        report.getEntries().stream()
            .map(
                entry -> {
                  if (entry.focusNode() == null) {
                    return "null";
                  }
                  return entry.focusNode().isBlank() ? "_:blank" : entry.focusNode().toString();
                })
            .sorted()
            .toList();
    return report.getEntries().size() + "|" + String.join("|", focusNodes);
  }

  private record ViolationSnapshot(int count, String signature, String source) {
    private static ViolationSnapshot unknown() {
      return new ViolationSnapshot(-1, "", "unknown");
    }

    private boolean hasSignature() {
      return signature != null && !signature.isBlank();
    }
  }

  /** Gets the count of shapes that have been marked as processed. */
  private int getCompletedShapesCount() {
    if (shacl == null || shacl.getShapes() == null) {
      return 0;
    }
    return (int) shacl.getShapes().stream().filter(Shacl.Shape::isProcessed).count();
  }

  /**
   * Counts how many processed shapes have no related validation violations. A shape is considered
   * to have "no violations" if it has been marked as processed and there are no validation report
   * entries targeting instances of that shape's target class.
   */
  private int countShapesWithNoViolations() {
    if (shacl == null || shacl.getShapes() == null) {
      return 0;
    }

    DataModelSnapshotResolver.DataModelValidation validation = resolveCurrentDataModelValidation();
    if (validation == null) {
      return 0;
    }

    Model dataModel = validation.model();
    ValidationReport report = validation.report();

    int count = 0;
    for (Shacl.Shape shape : shacl.getShapes()) {
      if (!shape.isProcessed()) {
        continue;
      }

      String targetClassUri = ShapeValidationMatcher.getTargetClassUri(shape).orElse(null);
      if (targetClassUri == null) {
        continue;
      }

      Resource targetClass = dataModel.createResource(targetClassUri);
      if (!ShapeValidationMatcher.hasViolationsForTargetClass(
          dataModel, shacl.getOntology(), report, targetClass)) {
        count++;
      }
    }

    return count;
  }

  /**
   * Gets the current data model together with its SHACL validation report, reusing the shared
   * triple store's cached validation when available instead of re-validating the same unchanged
   * model.
   */
  private DataModelSnapshotResolver.DataModelValidation resolveCurrentDataModelValidation() {
    return DataModelSnapshotResolver.resolve(
        sharedTripleStore, shacl, config != null ? config.getOutputPath() : null);
  }

  private void runFinalizationWithBenchmark() {
    int poolCount = (config != null) ? config.getPoolCount() : 1;
    if (poolCount <= 1 || supervisor == null) {
      return;
    }

    logger.info("All shapes processed - Supervisor directly editing output for consistency");

    for (int attempt = 1; attempt <= MAX_FINALIZATION_ATTEMPTS; attempt++) {
      try {
        long finalizationStart = System.currentTimeMillis();
        supervisor.finalizeOutput();
        captureBenchmarkSnapshot("FINALIZING", finalizationStart);
        return;
      } catch (Exception e) {
        logger.error(
            "Finalization error (attempt {}/{}): {}",
            attempt,
            MAX_FINALIZATION_ATTEMPTS,
            e.getMessage(),
            e);
      }
    }

    logger.error("Finalization failed after {} attempts", MAX_FINALIZATION_ATTEMPTS);
  }

  private void runReviewWithBenchmark() {
    if (reviewCoordinator == null) {
      return;
    }

    logger.info("Running final review on finalized output");

    long reviewStart = System.currentTimeMillis();
    try {
      reviewCoordinator.review();

      if (reviewCoordinator.isReady()) {
        logger.info("Review completed with ACCEPTED decision");
        captureBenchmarkSnapshot("REVIEW_ACCEPTED", reviewStart);
        return;
      }

      if (reviewCoordinator.isError()) {
        logger.warn("Review ended without acceptance (REJECTED or terminal review failure)");
        captureBenchmarkSnapshot("REVIEW_REJECTED", reviewStart);
        return;
      }

      throw new IllegalStateException("Review ended without a terminal decision");
    } catch (Exception e) {
      logger.error("Review error: {}", e.getMessage(), e);
    }
  }

  private void logUnprocessedShapeNames(int processedShapes, int totalShapes) {
    if (shacl == null || shacl.getShapes() == null) {
      return;
    }

    List<String> unprocessedNames =
        shacl.getShapes().stream()
            .filter(shape -> !shape.isProcessed())
            .map(Shacl.Shape::getName)
            .toList();

    if (!unprocessedNames.isEmpty()) {
      logger.warn(
          "Unprocessed shapes ({}/{}): {}",
          unprocessedNames.size(),
          totalShapes - processedShapes,
          String.join(", ", unprocessedNames));
    }
  }
}
