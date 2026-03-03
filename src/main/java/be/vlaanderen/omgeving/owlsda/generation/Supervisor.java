package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.ShaclContext;
import be.vlaanderen.omgeving.owlsda.agent.context.ValidationContext;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private static final String DELEGATION_CONTEXT_NAME = "Delegation Instructions";
  private static final String WORKER_RESPONSES_CONTEXT_NAME = "Worker Responses";
  private static final int MAX_WORKER_RESPONSE_CHARS = 1200;

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

      // First pass has no initial report; follow-up passes can start from a known report
      String report = firstPass ? null : validator.validate();

      return delegate(shapes, report, firstPass);
    } catch (Exception e) {
      logger.error("Supervisor orchestration failed: {}", e.getMessage(), e);
      return false;
    }
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

      // Reset all worker delegation contexts for this round; only explicitly delegated workers get new instructions.
      clearWorkerDelegationInstructions();

      // Always provide SHACL scope for delegation and fixes.
      int contextEnd = Math.min(shapes, getUnprocessedShapes().size());
      if (contextEnd > 0) {
        supervisorSession.addContextIfChanged(new ShaclContext(shacl, 0, contextEnd, shacl.getShapes().size()));
      }

      boolean hasValidationReport = validationReport != null && !validationReport.isEmpty();

      if (hasValidationReport) {
        supervisorSession.addContext(new ValidationContext(validationReport));
      }

      String instructions = buildDelegationInstructions(shapes, firstPass, hasValidationReport);
      String workerInstructions = buildWorkerInstructions(firstPass, hasValidationReport);

      flushSharedStoreBeforeSupervisorPrompt();
      ResponseMessage response = supervisorSession.prompt(new RequestMessage(instructions)).get();
      logger.info("Supervisor delegation response: {}", response.getMessage());

      List<Session> delegatedWorkerSessions = getDelegatedWorkerSessions();
      int delegatedWorkerCount = delegatedWorkerSessions.size();
      if (delegatedWorkerCount == 0) {
        logger.warn("Supervisor did not delegate tasks in first attempt; issuing strict retry prompt");
        delegatedWorkerCount = forceDelegationRetry(shapes, hasValidationReport);
        delegatedWorkerSessions = getDelegatedWorkerSessions();
      }

      if (delegatedWorkerCount == 0) {
        logger.error("Supervisor did not delegate any tasks to workers for this round; aborting round to avoid infinite retry loop");
        return false;
      }

      logger.info("Delegation round assigned tasks to {} worker(s)", delegatedWorkerCount);
      boolean batchSuccess = concurrentWorkerBatch.runWithDelegation(workerInstructions, shapes);
      publishWorkerResponsesToSupervisor(delegatedWorkerSessions);
      if (batchSuccess) {
        markDelegatedShapesAsProcessed(delegatedWorkerCount);
      }
      return batchSuccess;
    } catch (Exception e) {
      logger.error("Failed to delegate: {}", e.getMessage(), e);
      return false;
    }
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
    String shapeDistribution = calculateOptimalDistribution(shapes);

    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("targetAgents", getTargetAgentRange());
    placeholders.put("availableShapesSummary", getAvailableShapesSummary());
    placeholders.put("validationReportHint", validationContextHint);
    placeholders.put("iterationGuidance", iterationGuidance);
    placeholders.put("batchSize", String.valueOf(shapes));
    placeholders.put("shapeDistribution", shapeDistribution);

    return InstructionFactory.render("supervisor-delegation-generation", placeholders);
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

        List<String> assignedNames = new ArrayList<>();
        for (int i = startShape; i < endShape; i++) {
          assignedNames.add(unprocessedShapes.get(i).getName());
        }

        distribution.append(String.join(", ", assignedNames));
        distribution.append("\n");
      }
    }

    distribution.append("\nDelegate to workers above with their assigned shapes.\n");
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
    return InstructionFactory.render("worker-instructions-generation", placeholders);
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

  private void markDelegatedShapesAsProcessed(int delegatedWorkerCount) {
    List<Shacl.Shape> unprocessedShapes = getUnprocessedShapes();
    if (unprocessedShapes.isEmpty()) {
      return;
    }

    int batchSize = (concurrentWorkerBatch.config() != null)
        ? concurrentWorkerBatch.config().getBatchSize()
        : 1;
    int shapesToMark = Math.min(unprocessedShapes.size(), delegatedWorkerCount * batchSize);
    if (shapesToMark <= 0) {
      return;
    }

    List<String> completedShapeNames = new ArrayList<>();
    for (int i = 0; i < shapesToMark; i++) {
      Shacl.Shape shape = unprocessedShapes.get(i);
      shape.setProcessed(true);
      completedShapeNames.add(shape.getName());
    }

    if (shapeProcessingTracker != null) {
      shapeProcessingTracker.markCompleted(completedShapeNames);
    }

    logger.info("Marked {} shape(s) as processed: {}", shapesToMark, String.join(", ", completedShapeNames));
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
   * Clear delegation context for all workers at the start of each delegation round so workers without new assignments do not retain stale instructions.
   */
  private void clearWorkerDelegationInstructions() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return;
    }

    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      Context clearedDelegation = new Context();
      clearedDelegation.setName(DELEGATION_CONTEXT_NAME);
      clearedDelegation.setType("text/plain");
      clearedDelegation.setContent("");
      workerSession.addContextIfChanged(clearedDelegation);
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
      if (DELEGATION_CONTEXT_NAME.equals(context.getName())) {
        String content = context.getContent();
        return content != null && !content.isBlank();
      }
    }
    return false;
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
      ResponseMessage retryResponse = supervisorSession.prompt(new RequestMessage(retryInstruction)).get();
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
