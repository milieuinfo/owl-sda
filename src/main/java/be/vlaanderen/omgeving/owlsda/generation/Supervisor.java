package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.ShaclContext;
import be.vlaanderen.omgeving.owlsda.agent.context.ValidationContext;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervisor coordinates workers in batches and directly edits output data once all shapes are
 * finalized. The shared triple store automatically merges worker contributions.
 */
public record Supervisor(
    Session supervisorSession,
    OutputValidator validator,
    ConcurrentWorkerBatch concurrentWorkerBatch,
    Shacl shacl,
    WorkerTripleStore sharedTripleStore,
    ShapeProcessingTracker shapeProcessingTracker,
    OutputValidator finalizationValidator) {

  private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);
  private static final String DELEGATION_CONTEXT_NAME = DelegationHandler.DELEGATION_CONTEXT_NAME;

  /**
   * Convenience constructor for callers that validate consistently everywhere (all current
   * production wiring except {@link #finalizeOutput}'s gate, and every test). Defaults {@link
   * #finalizationValidator} to the same {@code validator} passed in.
   */
  public Supervisor(
      Session supervisorSession,
      OutputValidator validator,
      ConcurrentWorkerBatch concurrentWorkerBatch,
      Shacl shacl,
      WorkerTripleStore sharedTripleStore,
      ShapeProcessingTracker shapeProcessingTracker) {
    this(
        supervisorSession,
        validator,
        concurrentWorkerBatch,
        shacl,
        sharedTripleStore,
        shapeProcessingTracker,
        validator);
  }

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
    if (sharedTripleStore == null || sharedTripleStore.size() == 0) {
      return null;
    }

    DataModelSnapshotResolver.DataModelValidation validation =
        DataModelSnapshotResolver.resolve(
            sharedTripleStore, shacl, validator != null ? validator.getOutputPath() : null);
    if (validation == null || validation.report().conforms()) {
      return null;
    }

    return formatValidationReport(validation.report(), "store");
  }

  private String formatValidationReport(ValidationReport report, String source) {
    StringWriter reportString = new StringWriter();
    reportString
        .append("Data does NOT conform to SHACL shapes (source='")
        .append(source)
        .append("'). Violations:\n");
    report.getModel().write(reportString, "TURTLE");
    return reportString.toString();
  }

  /** Unified delegation method that handles both generation and follow-up fixes. */
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

      List<Session> delegatedWorkerSessions =
          requestDelegationAssignments(shapes, instructions, hasValidationReport);
      if (delegatedWorkerSessions.isEmpty()) {
        logger.error(
            "Supervisor did not delegate any tasks to workers for this round; aborting round to avoid infinite retry loop");
        return false;
      }

      return executeDelegatedRound(shapes, workerInstructions, delegatedWorkerSessions);
    } catch (Exception e) {
      logger.error("Failed to delegate: {}", e.getMessage(), e);
      return false;
    }
  }

  private boolean prepareDelegationRound(int shapes, String validationReport) {
    new WorkerDelegationContextManager(concurrentWorkerBatch).clearDelegationInstructions();

    if (shacl != null && shacl.getShapes() != null && !shacl.getShapes().isEmpty()) {
      int unprocessedShapeCount =
          (int) shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).count();
      int scopedShapeCount = Math.min(Math.max(shapes, 1), unprocessedShapeCount);
      if (scopedShapeCount > 0) {
        supervisorSession.addContextIfChanged(
            new ShaclContext(shacl, 0, scopedShapeCount, unprocessedShapeCount));
      }
    }

    boolean hasValidationReport = validationReport != null && !validationReport.isEmpty();
    if (hasValidationReport) {
      supervisorSession.addContext(new ValidationContext(validationReport));
    }
    return hasValidationReport;
  }

  private List<Session> requestDelegationAssignments(
      int shapes, String instructions, boolean hasValidationReport) throws Exception {
    flushSharedStoreBeforeSupervisorPrompt();
    ResponseMessage response = supervisorSession.prompt(new RequestMessage(instructions)).get();
    logger.info("Supervisor delegation response: {}", response.getMessage());

    List<Session> delegatedWorkerSessions = getDelegatedWorkerSessions();
    int delegatedWorkerCount = delegatedWorkerSessions.size();
    if (delegatedWorkerCount == 0) {
      logger.warn(
          "Supervisor did not delegate tasks in first attempt; issuing strict retry prompt");
      forceDelegationRetry(shapes, hasValidationReport);
      delegatedWorkerSessions = getDelegatedWorkerSessions();
    }

    return delegatedWorkerSessions;
  }

  private boolean executeDelegatedRound(
      int shapes, String workerInstructions, List<Session> delegatedWorkerSessions)
      throws Exception {
    int delegatedWorkerCount = delegatedWorkerSessions.size();
    logger.info("Delegation round assigned tasks to {} worker(s)", delegatedWorkerCount);

    boolean batchSuccess = concurrentWorkerBatch.runWithDelegation(workerInstructions, shapes);
    new WorkerResponsePublisher(concurrentWorkerBatch)
        .publish(supervisorSession, delegatedWorkerSessions);

    int progressMarkedShapes =
        new WorkerProgressReportParser(shacl, shapeProcessingTracker)
            .markCompletedShapesFromWorkerProgress(delegatedWorkerSessions);
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

  /**
   * Builds supervisor instructions for delegation with optimized worker distribution. Instructions
   * are loaded from resource files via InstructionFactory.
   */
  private String buildDelegationInstructions(
      int shapes, boolean firstPass, boolean hasValidationReport) {
    String iterationGuidance = buildIterationGuidance(firstPass);

    String validationContextHint =
        hasValidationReport
            ? "A 'Validation Report' context is available; prioritize unresolved violations and avoid repeating unchanged instructions.\n\n"
            : "";

    // Calculate optimal shape distribution across all workers
    Map<String, String> placeholders =
        getPlaceholders(shapes, validationContextHint, iterationGuidance);

    return InstructionFactory.render("supervisor-delegation-generation", placeholders);
  }

  private Map<String, String> getPlaceholders(
      int shapes, String validationContextHint, String iterationGuidance) {
    String shapeDistribution =
        ShapeDistributionFormatter.format(shacl, concurrentWorkerBatch, shapes);

    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("targetAgents", getTargetAgentRange());
    placeholders.put("availableShapesSummary", getAvailableShapesSummary());
    placeholders.put("validationReportHint", validationContextHint);
    placeholders.put("iterationGuidance", iterationGuidance);
    placeholders.put("batchSize", String.valueOf(shapes));
    placeholders.put("shapeDistribution", shapeDistribution);
    return placeholders;
  }

  /** Builds worker instructions for both initial generation and follow-up fixes. */
  private String buildWorkerInstructions(boolean firstPass, boolean hasValidationReport) {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("delegationContextName", DELEGATION_CONTEXT_NAME);

    String validationContextHint =
        hasValidationReport
            ? "Use the 'Validation Report' context to resolve remaining violations.\n"
            : "";

    String iterationHint =
        firstPass
            ? ""
            : "This is a follow-up pass: update only unresolved issues and avoid repeating unchanged instructions.\n";

    placeholders.put("validationReportHint", validationContextHint + iterationHint);
    placeholders.put("dataRichnessHint", buildWorkerDataRichnessHint());
    placeholders.put("namespaceReminder", buildNamespaceReminder());
    return InstructionFactory.render("worker-instructions-generation", placeholders);
  }

  private String buildNamespaceReminder() {
    String namespace = shacl != null ? shacl.resolvePrimaryNamespace() : null;
    if (namespace == null) {
      return "";
    }
    return "IMPORTANT: The ontology has exactly ONE namespace: <"
        + namespace
        + ">. Type every new instance's rdf:type with an IRI in this EXACT namespace (e.g. <"
        + namespace
        + "YourClass>, or declare `@prefix : <"
        + namespace
        + ">` yourself and use `:YourClass`)."
        + " Do NOT bind `:`/`ex:` or any other prefix to a different namespace, and do NOT invent"
        + " any other namespace either - not a generic placeholder, not a variant of this one, not"
        + " a per-topic or per-class namespace of your own. Every class lives in the ONE namespace"
        + " above, with no exceptions.\n";
  }

  private Config.DataRichness resolveDataRichness() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.config() == null) {
      return Config.DataRichness.MINIMAL;
    }
    return concurrentWorkerBatch.config().getDataRichness();
  }

  private String buildWorkerDataRichnessHint() {
    return switch (resolveDataRichness()) {
      case MINIMAL ->
          "RICHNESS PROFILE: MINIMAL\n- Generate only required SHACL-constrained triples plus minimal identifiers/labels needed for coherence.\n- Avoid inventing new predicates unless absolutely necessary to express a required fact.\n";
      case BALANCED ->
          "RICHNESS PROFILE: BALANCED\n- Generate required SHACL triples and a small set of realistic optional predicates that improve interpretability.\n- Prefer ontology predicates first; invent new predicates only when there is a clear domain need not covered by existing vocabulary.\n";
      case RICH ->
          "RICHNESS PROFILE: RICH\n- Generate required SHACL triples and substantial realistic optional enrichment (context, relationships, provenance, temporal details).\n- You may invent new predicates for useful domain facts when existing ontology predicates are insufficient. Keep them consistent and reusable.\n";
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
    if (shacl == null
        || shacl.getShapes() == null
        || shacl.getShapes().isEmpty()
        || sharedTripleStore == null
        || sharedTripleStore.size() == 0) {
      return;
    }

    try {
      WorkerTripleStore.ValidationSnapshot snapshot =
          sharedTripleStore.getValidationSnapshot(shacl);
      Model model = snapshot.model();
      ValidationReport validationReport = snapshot.report();
      if (validationReport == null) {
        return;
      }

      List<String> reopenedShapeNames =
          ShapeValidationMatcher.reopenProcessedShapesWithViolations(
              shacl.getShapes(), model, shacl.getOntology(), validationReport);

      if (!reopenedShapeNames.isEmpty()) {
        logger.warn(
            "Reopened {} processed shape(s) due to active class-related violations: {}",
            reopenedShapeNames.size(),
            String.join(", ", reopenedShapeNames));
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

    int batchSize =
        (concurrentWorkerBatch.config() != null)
            ? concurrentWorkerBatch.config().getBatchSize()
            : 1;
    int shapesToMark = Math.min(unprocessedShapes.size(), delegatedWorkerCount * batchSize);
    if (shapesToMark <= 0) {
      logger.warn("shapesToMark calculated as {}, returning", shapesToMark);
      return;
    }

    logger.info(
        "Evaluating {} shapes for completion (batchSize={}, delegatedWorkerCount={})",
        shapesToMark,
        batchSize,
        delegatedWorkerCount);

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(sharedTripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch completionBatch =
        evaluator.evaluate(unprocessedShapes, shapesToMark);

    for (Shacl.Shape completedShape : completionBatch.completedShapes()) {
      completedShape.setProcessed(true);
    }

    if (shapeProcessingTracker != null && !completionBatch.completedShapeNames().isEmpty()) {
      shapeProcessingTracker.markCompleted(completionBatch.completedShapeNames());
    }

    if (!completionBatch.completedShapeNames().isEmpty()) {
      logger.info(
          "Marked {} shape(s) as processed: {}",
          completionBatch.completedShapeNames().size(),
          String.join(", ", completionBatch.completedShapeNames()));
    }

    if (!completionBatch.skippedShapeNames().isEmpty()) {
      logger.warn(
          "Skipped marking {} shape(s) as processed (no instances generated): {}",
          completionBatch.skippedShapeNames().size(),
          String.join(", ", completionBatch.skippedShapeNames()));
    }

    if (!completionBatch.skippedByValidationShapeNames().isEmpty()) {
      logger.warn(
          "Skipped marking {} shape(s) as processed (class-related validation violations remain): {}",
          completionBatch.skippedByValidationShapeNames().size(),
          String.join(", ", completionBatch.skippedByValidationShapeNames()));
    }
  }

  private static final int MAX_FINALIZATION_ITERATIONS = 5;

  /** Finalize output by directly editing for consistency and documentation. */
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

      // Validate using finalizationValidator - NOT validator - so this gate checks the file
      // against the SAME shapes the supervisor's own shacl_validator(source="file") tool call
      // just used (see SessionManager, where the supervisor/reviewer's tool is wired to
      // inferredShacl while plain `validator` is wired to defaultShacl for worker-facing reports).
      // Using `validator` here previously meant this loop could re-open finalization with
      // "Validation still reports issues" right after the supervisor's own tool call reported 0
      // violations on the same file - the supervisor, seeing its own check pass, never touches
      // anything meaningfully different on the next iteration, so the mismatch could repeat.
      String validationReport = finalizationValidator.validate();
      int iteration = 0;
      while (validationReport != null && !validationReport.isEmpty()) {
        iteration++;
        if (iteration > MAX_FINALIZATION_ITERATIONS) {
          logger.error(
              "Stopping finalization: validation still reports issues after {} iterations",
              MAX_FINALIZATION_ITERATIONS);
          return;
        }
        logger.info(
            "Output validation failed during finalization, iterating with supervisor edits");
        supervisorSession.addContext(new ValidationContext(validationReport));
        String iterationInstruction = InstructionFactory.load("supervisor-finalization-iteration");
        supervisorSession.prompt(new RequestMessage(iterationInstruction)).get();
        validationReport = finalizationValidator.validate();
      }

      logger.info("Output finalization complete");
    } catch (Exception e) {
      logger.error("Supervisor finalization failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Handle review feedback with supervisor control over delegation. Supervisor decides whether to
   * handle feedback directly or delegate to workers.
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

  public int getDelegatedWorkerCount() {
    return new WorkerDelegationContextManager(concurrentWorkerBatch).getDelegatedWorkerCount();
  }

  private List<Session> getDelegatedWorkerSessions() {
    return new WorkerDelegationContextManager(concurrentWorkerBatch).getDelegatedWorkerSessions();
  }

  private String getTargetAgentRange() {
    int workerCount = concurrentWorkerBatch.getWorkerCount();
    return workerCount <= 1 ? "POOL-0" : "POOL-0..POOL-" + (workerCount - 1);
  }

  /** Gets a summary of available unprocessed shapes for delegation context. */
  private String getAvailableShapesSummary() {
    if (shacl == null || shacl.getShapes() == null || shacl.getShapes().isEmpty()) {
      return "No shapes available";
    }

    long unprocessedCount =
        shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).count();

    return String.format(
        "%d shapes available (%d unprocessed)", shacl.getShapes().size(), unprocessedCount);
  }

  private int forceDelegationRetry(int shapes, boolean hasValidationReport) {
    try {
      String retryInstruction =
          buildMandatoryDelegationRetryInstruction(shapes, hasValidationReport);
      flushSharedStoreBeforeSupervisorPrompt();
      ResponseMessage retryResponse =
          supervisorSession
              .prompt(
                  new RequestMessage(retryInstruction, "supervisor-delegation-generation-retry"))
              .get();
      logger.info("Supervisor strict delegation retry response: {}", retryResponse.getMessage());
      return getDelegatedWorkerCount();
    } catch (Exception e) {
      logger.error("Strict delegation retry failed: {}", e.getMessage(), e);
      return 0;
    }
  }

  private String buildMandatoryDelegationRetryInstruction(int shapes, boolean hasValidationReport) {
    StringBuilder instruction = new StringBuilder();
    instruction
        .append(
            "Previous response delegated to 0 workers. Delegation contexts are reset every round, ")
        .append(
            "so prior assignments do not count. You MUST now publish concrete new instructions via delegate_tasks.\n")
        .append("Target agents: ")
        .append(getTargetAgentRange())
        .append(".\n")
        .append("Available shapes: ")
        .append(getAvailableShapesSummary())
        .append(".\n")
        .append("Batch size: ")
        .append(shapes)
        .append(".\n");

    if (hasValidationReport) {
      instruction.append(
          "A Validation Report context is available. Delegate unresolved fixes to workers now.\n");
    } else {
      instruction.append("Delegate remaining generation work now.\n");
    }

    instruction.append(
        "Call delegate_tasks at least once with non-empty instructions, one target_agent per call.");
    return instruction.toString();
  }
}
