package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.ShaclContext;
import be.vlaanderen.omgeving.owlsda.agent.context.ValidationContext;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervisor coordinates workers in batches and directly edits output data once all shapes are
 * finalized. The shared triple store automatically merges worker contributions.
 */
public record Supervisor(Session supervisorSession, OutputValidator validator,
                         ConcurrentWorkerBatch concurrentWorkerBatch, Shacl shacl,
                         WorkerTripleStore sharedTripleStore,
                         ShapeProcessingTracker shapeProcessingTracker) {

  private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);
  private static final String DELEGATION_CONTEXT_NAME = DelegationHandler.DELEGATION_CONTEXT_NAME;
  private static final String WORKER_RESPONSES_CONTEXT_NAME = "Worker Responses";
  private static final String WORKER_PROGRESS_CONTEXT_NAME = WorkerProgressHandler.CONTEXT_NAME;
  private static final int MAX_WORKER_RESPONSE_CHARS = 1200;
  private static final int MAX_SHAPE_NAMES_PER_WORKER_IN_DISTRIBUTION = 4;

  public boolean orchestrate(int shapes, boolean firstPass) {
    try {
      if (concurrentWorkerBatch == null) {
        logger.error("Cannot orchestrate: concurrent worker batch is null");
        return false;
      }

      if (validator == null) {
        logger.error("Cannot orchestrate: validator is null");
        return false;
      }

      // First pass has no initial report; follow-up passes should use live shared-store validation.
      String report = firstPass ? null : getDelegationValidationReport();

      return delegate(shapes, report, firstPass);
    } catch (Exception e) {
      logger.error("Supervisor orchestration failed: {}", e.getMessage(), e);
      return false;
    }
  }

  private String getDelegationValidationReport() {
    String sharedStoreReport = validateSharedStore();
    if (sharedStoreReport != null) {
      return sharedStoreReport;
    }

    return validator.validate();
  }

  private String validateSharedStore() {
    if (sharedTripleStore == null || sharedTripleStore.size() == 0 || shacl == null) {
      return null;
    }

    try {
      ValidationReport report = shacl.validate(sharedTripleStore.getModel());
      if (report.conforms()) {
        return null;
      }

      return formatValidationReport(report, "store");
    } catch (Exception e) {
      logger.warn("Failed shared-store validation for delegation, falling back to file validation: {}",
          e.getMessage());
      return null;
    }
  }

  private String formatValidationReport(ValidationReport report, String source) {
    StringWriter reportString = new StringWriter();
    reportString.append("Data does NOT conform to SHACL shapes (source='")
        .append(source)
        .append("'). Violations:\n");
    report.getModel().write(reportString, "TURTLE");
    return reportString.toString();
  }

  /**
   * Unified delegation method that handles both generation and follow-up fixes.
   */
  private boolean delegate(int shapes, String validationReport, boolean firstPass) {
    try {
      if (supervisorSession == null) {
        logger.error("Cannot delegate: supervisor session is null");
        return false;
      }

      reopenShapesWithOutstandingViolations();

      boolean hasValidationReport = prepareDelegationRound(shapes, validationReport);
      String instructions = buildDelegationInstructions(shapes, firstPass, hasValidationReport);
      String workerInstructions = buildWorkerInstructions(firstPass, hasValidationReport);

      List<Session> delegatedWorkerSessions = requestDelegationAssignments(shapes, instructions,
          hasValidationReport);
      if (delegatedWorkerSessions.isEmpty()) {
        logger.error("Supervisor did not delegate any tasks to workers for this round; aborting round to avoid infinite retry loop");
        return false;
      }

      return executeDelegatedRound(shapes, workerInstructions, delegatedWorkerSessions);
    } catch (Exception e) {
      logger.error("Failed to delegate: {}", e.getMessage(), e);
      return false;
    }
  }

  private boolean prepareDelegationRound(int shapes, String validationReport) {
    clearWorkerDelegationInstructions();

    if (shacl != null && shacl.getShapes() != null && !shacl.getShapes().isEmpty()) {
      int unprocessedShapeCount = (int) shacl.getShapes().stream()
          .filter(shape -> !shape.isProcessed())
          .count();
      int scopedShapeCount = Math.min(Math.max(shapes, 1), unprocessedShapeCount);
      if (scopedShapeCount > 0) {
        supervisorSession.addContextIfChanged(new ShaclContext(shacl, 0, scopedShapeCount,
            unprocessedShapeCount));
      }
    }

    boolean hasValidationReport = validationReport != null && !validationReport.isEmpty();
    if (hasValidationReport) {
      supervisorSession.addContext(new ValidationContext(validationReport));
    }
    return hasValidationReport;
  }

  private List<Session> requestDelegationAssignments(int shapes, String instructions,
                                                     boolean hasValidationReport) throws Exception {
    flushSharedStoreBeforeSupervisorPrompt();
    ResponseMessage response = supervisorSession.prompt(new RequestMessage(instructions)).get();
    logger.info("Supervisor delegation response: {}", response.getMessage());

    List<Session> delegatedWorkerSessions = getDelegatedWorkerSessions();
    int delegatedWorkerCount = delegatedWorkerSessions.size();
    if (delegatedWorkerCount == 0) {
      logger.warn("Supervisor did not delegate tasks in first attempt; issuing strict retry prompt");
      forceDelegationRetry(shapes, hasValidationReport);
      delegatedWorkerSessions = getDelegatedWorkerSessions();
    }

    return delegatedWorkerSessions;
  }

  private boolean executeDelegatedRound(int shapes, String workerInstructions,
                                        List<Session> delegatedWorkerSessions) throws Exception {
    int delegatedWorkerCount = delegatedWorkerSessions.size();
    logger.info("Delegation round assigned tasks to {} worker(s)", delegatedWorkerCount);

    boolean batchSuccess = concurrentWorkerBatch.runWithDelegation(workerInstructions, shapes);
    publishWorkerResponsesToSupervisor(delegatedWorkerSessions);

    int progressMarkedShapes = markCompletedShapesFromWorkerProgress(delegatedWorkerSessions);
    if (batchSuccess) {
      markDelegatedShapesAsProcessed(delegatedWorkerCount);
    }

    boolean roundSuccess = batchSuccess || progressMarkedShapes > 0;
    if (!batchSuccess && progressMarkedShapes > 0) {
      logger.warn(
          "Worker batch finished with failures/timeouts, but {} shape(s) were marked as processed from structured worker progress",
          progressMarkedShapes);
    }

    return roundSuccess;
  }

  private int markCompletedShapesFromWorkerProgress(List<Session> delegatedWorkerSessions) {
    if (delegatedWorkerSessions == null || delegatedWorkerSessions.isEmpty()
        || shacl == null || shacl.getShapes() == null || shacl.getShapes().isEmpty()) {
      return 0;
    }

    Set<String> completedShapeNames = new HashSet<>();
    for (Session workerSession : delegatedWorkerSessions) {
      if (workerSession == null) {
        continue;
      }

      String workerReport = getWorkerProgressReport(workerSession);
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

        boolean exists = shacl.getShapes().stream()
            .anyMatch(shape -> shapeName.equals(shape.getName()));
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

    logger.info("Marked {} shape(s) as processed from worker progress: {}",
        completedShapeNames.size(), String.join(", ", completedShapeNames));
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

    boolean acceptableStatus = "CREATED".equals(status)
        || "FIXED".equals(status)
        || "VERIFIED_NO_CHANGE".equals(status);
    boolean conforms = "CONFORMS".equals(validationResult);
    return acceptableStatus && conforms;
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

  /**
   * Builds supervisor instructions for delegation with optimized worker distribution.
   * Instructions are loaded from resource files via InstructionFactory.
   */
  private String buildDelegationInstructions(int shapes, boolean firstPass, boolean hasValidationReport) {
    String iterationGuidance = buildIterationGuidance(firstPass);

    String validationContextHint = hasValidationReport
        ? "A 'Validation Report' context is available; prioritize unresolved violations and avoid repeating unchanged instructions.\n\n"
        : "";

    // Calculate optimal shape distribution across all workers
    Map<String, String> placeholders = getPlaceholders(shapes, validationContextHint,
        iterationGuidance);

    return InstructionFactory.render("supervisor-delegation-generation", placeholders);
  }

  private Map<String, String> getPlaceholders(int shapes, String validationContextHint,
      String iterationGuidance) {
    String shapeDistribution = calculateOptimalDistribution(shapes);

    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("targetAgents", getTargetAgentRange());
    placeholders.put("availableShapesSummary", getAvailableShapesSummary());
    placeholders.put("validationReportHint", validationContextHint);
    placeholders.put("iterationGuidance", iterationGuidance);
    placeholders.put("batchSize", String.valueOf(shapes));
    placeholders.put("shapeDistribution", shapeDistribution);
    return placeholders;
  }

  /**
   * Calculates optimal shape distribution across all workers to maximize parallelism.
   * Each worker gets exactly batch_size shapes (configured value).
   * Total shapes assigned = batch_size * worker_count.
   */
  private String calculateOptimalDistribution(int totalShapes) {
    int workerCount = concurrentWorkerBatch.getWorkerCount();
    if (workerCount <= 0) {
      return "";
    }

    List<Shacl.Shape> unprocessedShapes = getUnprocessedShapes();
    if (unprocessedShapes.isEmpty()) {
      return "";
    }

    int batchSize = (concurrentWorkerBatch.config() != null)
        ? concurrentWorkerBatch.config().getBatchSize()
        : 1;

    int totalShapesToAssign = batchSize * workerCount;
    int actualShapesToAssign = Math.min(totalShapesToAssign, Math.min(totalShapes, unprocessedShapes.size()));

    StringBuilder distribution = new StringBuilder();
    distribution.append("OPTIMIZED SHAPE DISTRIBUTION (batch-size=").append(batchSize)
        .append(", ").append(workerCount).append(" workers, ")
        .append(actualShapesToAssign).append(" total shapes):\n");

    for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
      int startShape = workerIndex * batchSize;
      int endShape = Math.min(startShape + batchSize, actualShapesToAssign);

      if (startShape < actualShapesToAssign) {
        distribution.append(String.format("POOL-%d: shapes [%d..%d) - ", workerIndex, startShape, endShape));

        int maxNames = Math.min(endShape, startShape + MAX_SHAPE_NAMES_PER_WORKER_IN_DISTRIBUTION);
        List<String> sampleNames = new ArrayList<>();
        for (int i = startShape; i < maxNames; i++) {
          sampleNames.add(unprocessedShapes.get(i).getName());
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
    return distribution.toString();
  }

  /**
   * Builds worker instructions for both initial generation and follow-up fixes.
   */
  private String buildWorkerInstructions(boolean firstPass, boolean hasValidationReport) {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("delegationContextName", DELEGATION_CONTEXT_NAME);

    String validationContextHint = hasValidationReport
        ? "Use the 'Validation Report' context to resolve remaining violations.\n"
        : "";

    String iterationHint = firstPass
        ? ""
        : "This is a follow-up pass: update only unresolved issues and avoid repeating unchanged instructions.\n";

    placeholders.put("validationReportHint", validationContextHint + iterationHint);
    placeholders.put("dataRichnessHint", buildWorkerDataRichnessHint());
    return InstructionFactory.render("worker-instructions-generation", placeholders);
  }

  private Config.DataRichness resolveDataRichness() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.config() == null) {
      return Config.DataRichness.MINIMAL;
    }
    return concurrentWorkerBatch.config().getDataRichness();
  }

  private String buildWorkerDataRichnessHint() {
    return switch (resolveDataRichness()) {
      case MINIMAL -> "RICHNESS PROFILE: MINIMAL\n- Generate only required SHACL-constrained triples plus minimal identifiers/labels needed for coherence.\n- Avoid inventing new predicates unless absolutely necessary to express a required fact.\n";
      case BALANCED -> "RICHNESS PROFILE: BALANCED\n- Generate required SHACL triples and a small set of realistic optional predicates that improve interpretability.\n- Prefer ontology predicates first; invent new predicates only when there is a clear domain need not covered by existing vocabulary.\n";
      case RICH -> "RICHNESS PROFILE: RICH\n- Generate required SHACL triples and substantial realistic optional enrichment (context, relationships, provenance, temporal details).\n- You may invent new predicates for useful domain facts when existing ontology predicates are insufficient. Keep them consistent and reusable.\n";
    };
  }

  private String buildIterationGuidance(boolean firstPass) {
    if (firstPass) {
      return "First pass: delegate the next batch and keep instructions concise.\n\n";
    }

    return "Follow-up pass: only delegate unresolved work and avoid repeating prior instructions.\n\n";
  }

  private List<Shacl.Shape> getUnprocessedShapes() {
    if (shacl == null || shacl.getShapes() == null) {
      return List.of();
    }
    return shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).toList();
  }

  private void reopenShapesWithOutstandingViolations() {
    if (shacl == null || shacl.getShapes() == null || shacl.getShapes().isEmpty()
        || sharedTripleStore == null || sharedTripleStore.size() == 0) {
      return;
    }

    try {
      Model model = sharedTripleStore.getModel();
      ValidationReport validationReport = shacl.validate(model);
      if (validationReport == null) {
        return;
      }

      List<String> reopenedShapeNames = new ArrayList<>();
      for (Shacl.Shape shape : shacl.getShapes()) {
        if (!shape.isProcessed()) {
          continue;
        }

        String targetClassUri = ShapeValidationMatcher.getTargetClassUri(shape).orElse(null);
        if (targetClassUri == null) {
          continue;
        }

        Resource targetClass = model.createResource(targetClassUri);
        boolean hasClassViolations = ShapeValidationMatcher.hasViolationsForTargetClass(
            model, shacl != null ? shacl.getOntology() : null, validationReport, targetClass);
        if (hasClassViolations) {
          shape.setProcessed(false);
          reopenedShapeNames.add(shape.getName());
        }
      }

      if (!reopenedShapeNames.isEmpty()) {
        logger.warn("Reopened {} processed shape(s) due to active class-related violations: {}",
            reopenedShapeNames.size(), String.join(", ", reopenedShapeNames));
      }
    } catch (Exception e) {
      logger.warn("Failed to reopen shapes with outstanding violations: {}", e.getMessage());
    }
  }

  private void markDelegatedShapesAsProcessed(int delegatedWorkerCount) {
    List<Shacl.Shape> unprocessedShapes = getUnprocessedShapes();
    if (unprocessedShapes.isEmpty()) {
      logger.warn("No unprocessed shapes available to mark");
      return;
    }

    logger.info("Total unprocessed shapes: {}", unprocessedShapes.size());

    int batchSize = (concurrentWorkerBatch.config() != null)
        ? concurrentWorkerBatch.config().getBatchSize()
        : 1;
    int shapesToMark = Math.min(unprocessedShapes.size(), delegatedWorkerCount * batchSize);
    if (shapesToMark <= 0) {
      logger.warn("shapesToMark calculated as {}, returning", shapesToMark);
      return;
    }

    logger.info("Evaluating {} shapes for completion (batchSize={}, delegatedWorkerCount={})",
        shapesToMark, batchSize, delegatedWorkerCount);

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(sharedTripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch completionBatch = evaluator.evaluate(unprocessedShapes, shapesToMark);

    for (Shacl.Shape completedShape : completionBatch.completedShapes()) {
      completedShape.setProcessed(true);
    }

    if (shapeProcessingTracker != null && !completionBatch.completedShapeNames().isEmpty()) {
      shapeProcessingTracker.markCompleted(completionBatch.completedShapeNames());
    }

    if (!completionBatch.completedShapeNames().isEmpty()) {
      logger.info("Marked {} shape(s) as processed: {}", completionBatch.completedShapeNames().size(),
          String.join(", ", completionBatch.completedShapeNames()));
    }

    if (!completionBatch.skippedShapeNames().isEmpty()) {
      logger.warn("Skipped marking {} shape(s) as processed (no instances generated): {}",
          completionBatch.skippedShapeNames().size(), String.join(", ", completionBatch.skippedShapeNames()));
    }

    if (!completionBatch.skippedByValidationShapeNames().isEmpty()) {
      logger.warn("Skipped marking {} shape(s) as processed (class-related validation violations remain): {}",
          completionBatch.skippedByValidationShapeNames().size(),
          String.join(", ", completionBatch.skippedByValidationShapeNames()));
    }
  }

  /**
   * Finalize output by directly editing for consistency and documentation.
   */
  public void finalizeOutput() {
    try {
      if (supervisorSession == null) {
        logger.error("Cannot finalize: supervisor session is null");
        return;
      }

      logger.info("Finalizing output - editing for consistency and documentation");

      // Load finalization instruction from resource
      String finalizationInstruction = InstructionFactory.load("supervisor-finalization");
      flushSharedStoreBeforeSupervisorPrompt();
      supervisorSession.prompt(new RequestMessage(finalizationInstruction)).get();

      // Validate
      String validationReport = validator.validate();
      while (validationReport != null && !validationReport.isEmpty()) {
        logger.info("Output validation failed during finalization, iterating with supervisor edits");
        supervisorSession.addContext(new ValidationContext(validationReport));
        String iterationInstruction = InstructionFactory.load("supervisor-finalization-iteration");
        supervisorSession.prompt(new RequestMessage(iterationInstruction)).get();
        validationReport = validator.validate();
      }

      logger.info("Output finalization complete");
    } catch (Exception e) {
      logger.error("Supervisor finalization failed: {}", e.getMessage(), e);
    }
  }


  /**
   * Handle review feedback with supervisor control over delegation.
   * Supervisor decides whether to handle feedback directly or delegate to workers.
   *
   * @param reviewerFeedback The feedback from the reviewer
   * @return true if handled successfully (either directly or via delegation)
   */
  public boolean handleReviewFeedback(String reviewerFeedback) {
    try {
      if (supervisorSession == null) {
        logger.error("Cannot handle review feedback: supervisor session is null");
        return false;
      }

      // Ask supervisor to decide: handle directly or delegate
      Map<String, String> placeholders = new HashMap<>();
      placeholders.put("targetAgents", getTargetAgentRange());
      placeholders.put("reviewerFeedback", reviewerFeedback);
      String instructions = InstructionFactory.render("supervisor-review-decision", placeholders);

      flushSharedStoreBeforeSupervisorPrompt();
      ResponseMessage response = supervisorSession.prompt(new RequestMessage(instructions)).get();

      // The supervisor's response and tool usage will determine the approach
      // If supervisor used delegate_tasks, workers will handle it (output will be overwritten)
      // If supervisor used output editing tools, supervisor handled it directly

      logger.info("Supervisor processed review feedback: {}", response.getMessage());
      return true;
    } catch (Exception e) {
      logger.error("Supervisor review feedback handling failed: {}", e.getMessage(), e);
      return false;
    }
  }

  private void flushSharedStoreBeforeSupervisorPrompt() {
    if (sharedTripleStore == null) {
      return;
    }
    sharedTripleStore.flushToOutputFile("SUPERVISOR");
  }

  /**
   * Clear delegation context and stale progress report for all workers at the start of each
   * delegation round. Workers without new assignments must not retain stale instructions, and
   * the Worker Progress Report must be wiped so the supervisor cannot mistake a prior round's
   * result for current-round progress.
   * <p>
   * Each session is also reset (server-side message history cleared) to prevent context-window
   * saturation from accumulating conversation turns across many delegation rounds.
   */
  private void clearWorkerDelegationInstructions() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return;
    }

    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      List<String> delegationContextNames = workerSession.getContext().stream()
          .map(Context::getName)
          .filter(this::isDelegationContextName)
          .distinct()
          .toList();

      if (delegationContextNames.isEmpty()) {
        delegationContextNames = List.of(DELEGATION_CONTEXT_NAME);
      }

      for (String contextName : delegationContextNames) {
        Context clearedDelegation = new Context();
        clearedDelegation.setName(contextName);
        clearedDelegation.setType("text/plain");
        clearedDelegation.setContent("");
        workerSession.addContextIfChanged(clearedDelegation);
      }

      // Clear the Worker Progress Report so the supervisor does not read a stale result
      // from a previous round and mistake it for a report from the current round.
      Context clearedProgress = new Context();
      clearedProgress.setName(WORKER_PROGRESS_CONTEXT_NAME);
      clearedProgress.setType("text/plain");
      clearedProgress.setContent("");
      workerSession.addContextIfChanged(clearedProgress);

      // Reset server-side message history to prevent context-window overflow across rounds.
      workerSession.reset();
    }
  }

  public int getDelegatedWorkerCount() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return 0;
    }

    int delegatedWorkers = 0;
    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      if (hasActiveDelegationInstructions(workerSession)) {
        delegatedWorkers++;
      }
    }

    return delegatedWorkers;
  }

  private List<Session> getDelegatedWorkerSessions() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return List.of();
    }

    List<Session> delegated = new ArrayList<>();
    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      if (hasActiveDelegationInstructions(workerSession)) {
        delegated.add(workerSession);
      }
    }
    return delegated;
  }

  private boolean hasActiveDelegationInstructions(Session workerSession) {
    for (Context context : workerSession.getContext()) {
      String contextName = context.getName();
      if (!DELEGATION_CONTEXT_NAME.equals(contextName)) {
        continue;
      }

      String content = context.getContent();
      return content != null && !content.isBlank();
    }
    return false;
  }

  private boolean isDelegationContextName(String contextName) {
    return contextName != null && contextName.toLowerCase().contains("delegation");
  }

  private String getTargetAgentRange() {
    int workerCount = concurrentWorkerBatch.getWorkerCount();
    return workerCount <= 1 ? "POOL-0" : "POOL-0..POOL-" + (workerCount - 1);
  }

  /**
   * Gets a summary of available unprocessed shapes for delegation context.
   */
  private String getAvailableShapesSummary() {
    if (shacl == null || shacl.getShapes() == null || shacl.getShapes().isEmpty()) {
      return "No shapes available";
    }

    long unprocessedCount = shacl.getShapes().stream()
        .filter(shape -> !shape.isProcessed())
        .count();

    return String.format("%d shapes available (%d unprocessed)",
        shacl.getShapes().size(), unprocessedCount);
  }

  private int forceDelegationRetry(int shapes, boolean hasValidationReport) {
    try {
      String retryInstruction = buildMandatoryDelegationRetryInstruction(shapes, hasValidationReport);
      flushSharedStoreBeforeSupervisorPrompt();
      ResponseMessage retryResponse = supervisorSession.prompt(
          new RequestMessage(retryInstruction, "supervisor-delegation-generation-retry")
      ).get();
      logger.info("Supervisor strict delegation retry response: {}", retryResponse.getMessage());
      return getDelegatedWorkerCount();
    } catch (Exception e) {
      logger.error("Strict delegation retry failed: {}", e.getMessage(), e);
      return 0;
    }
  }

  private String buildMandatoryDelegationRetryInstruction(int shapes, boolean hasValidationReport) {
    StringBuilder instruction = new StringBuilder();
    instruction.append("Previous response delegated to 0 workers. Delegation contexts are reset every round, ")
        .append("so prior assignments do not count. You MUST now publish concrete new instructions via delegate_tasks.\n")
        .append("Target agents: ").append(getTargetAgentRange()).append(".\n")
        .append("Available shapes: ").append(getAvailableShapesSummary()).append(".\n")
        .append("Batch size: ").append(shapes).append(".\n");

    if (hasValidationReport) {
      instruction.append("A Validation Report context is available. Delegate unresolved fixes to workers now.\n");
    } else {
      instruction.append("Delegate remaining generation work now.\n");
    }

    instruction.append("Call delegate_tasks at least once with non-empty instructions, one target_agent per call.");
    return instruction.toString();
  }

  private void publishWorkerResponsesToSupervisor(List<Session> delegatedWorkerSessions) {
    if (supervisorSession == null || concurrentWorkerBatch == null
        || concurrentWorkerBatch.workerSessionPool() == null
        || delegatedWorkerSessions == null || delegatedWorkerSessions.isEmpty()) {
      return;
    }

    StringBuilder content = new StringBuilder();
    for (Session workerSession : delegatedWorkerSessions) {
      if (workerSession == null || !hasActiveDelegationInstructions(workerSession)) {
        continue;
      }

      String workerId = concurrentWorkerBatch.workerSessionPool().getSessionId(workerSession);
      if (workerId == null || workerId.isBlank() || "UNKNOWN".equals(workerId)) {
        continue;
      }

      String workerReport = getWorkerProgressReport(workerSession);
      if (workerReport != null && !workerReport.isBlank()) {
        content.append(workerId)
            .append(" [STRUCTURED_PROGRESS]:\n")
            .append(truncate(workerReport))
            .append("\n\n");
        // Progress report already provides full context; skip the raw message log.
        continue;
      }

      // No structured progress report for this round — fall back to latest message.
      SessionMessageLogEntry latest = getLatestWorkerResponse(workerSession);
      if (latest == null) {
        continue;
      }

      content.append(workerId)
          .append(" [")
          .append(latest.direction())
          .append("]:\n")
          .append(truncate(latest.content()))
          .append("\n\n");
    }

    if (content.isEmpty()) {
      return;
    }

    Context workerResponses = new Context();
    workerResponses.setName(WORKER_RESPONSES_CONTEXT_NAME);
    workerResponses.setType("text/plain");
    workerResponses.setContent(content.toString().trim());
    supervisorSession.addContextIfChanged(workerResponses);
  }

  private String getWorkerProgressReport(Session workerSession) {
    for (Context context : workerSession.getContext()) {
      if (WORKER_PROGRESS_CONTEXT_NAME.equals(context.getName())) {
        return context.getContent();
      }
    }
    return null;
  }

  private SessionMessageLogEntry getLatestWorkerResponse(Session workerSession) {
    List<SessionMessageLogEntry> entries = workerSession.getMessageLog();
    for (int i = entries.size() - 1; i >= 0; i--) {
      SessionMessageLogEntry entry = entries.get(i);
      if ("INBOUND".equals(entry.direction()) || "ERROR".equals(entry.direction())) {
        return entry;
      }
    }
    return null;
  }

  private String truncate(String value) {
    if (value == null) {
      return "";
    }
    if (value.length() <= Supervisor.MAX_WORKER_RESPONSE_CHARS) {
      return value;
    }
    return value.substring(0, Supervisor.MAX_WORKER_RESPONSE_CHARS) + "...";
  }
}
