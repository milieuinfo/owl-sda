package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.OutputContext;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.benchmark.DefaultBenchmarkSnapshotData;
import be.vlaanderen.omgeving.owlsda.exception.LanguageModelException;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.util.List;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates reviewer feedback and asks Supervisor to distribute tasks to workers. */
@Getter
public class SupervisorReviewCoordinator {
  private static final Logger logger = LoggerFactory.getLogger(SupervisorReviewCoordinator.class);
  private static final int DEFAULT_MAX_REVIEW_ITERATIONS = 3;

  private final Supplier<Session> reviewerSessionSupplier;
  private volatile Session reviewerSession;
  private final Supervisor supervisor;
  private final OutputValidator validator;
  private final BenchmarkService benchmarkService;
  private final WorkerTripleStore sharedTripleStore;
  private final int maxReviewIterations;

  @Setter private boolean ready = false;
  @Setter private boolean error = false;
  private volatile String reviewerFeedbackText = "";
  private volatile boolean reviewerDecisionReceived = false;

  public SupervisorReviewCoordinator(
      Session reviewerSession,
      Supervisor supervisor,
      OutputValidator validator,
      BenchmarkService benchmarkService,
      WorkerTripleStore sharedTripleStore) {
    this(
        () -> reviewerSession,
        supervisor,
        validator,
        benchmarkService,
        sharedTripleStore,
        DEFAULT_MAX_REVIEW_ITERATIONS);
  }

  public SupervisorReviewCoordinator(
      Session reviewerSession,
      Supervisor supervisor,
      OutputValidator validator,
      BenchmarkService benchmarkService,
      WorkerTripleStore sharedTripleStore,
      int maxReviewIterations) {
    this(
        () -> reviewerSession,
        supervisor,
        validator,
        benchmarkService,
        sharedTripleStore,
        maxReviewIterations);
  }

  public SupervisorReviewCoordinator(
      Supplier<Session> reviewerSessionSupplier,
      Supervisor supervisor,
      OutputValidator validator,
      BenchmarkService benchmarkService,
      WorkerTripleStore sharedTripleStore,
      int maxReviewIterations) {
    this.reviewerSessionSupplier =
        reviewerSessionSupplier == null ? () -> null : reviewerSessionSupplier;
    this.supervisor = supervisor;
    this.validator = validator;
    this.benchmarkService = benchmarkService;
    this.sharedTripleStore = sharedTripleStore;
    this.maxReviewIterations = Math.max(1, maxReviewIterations);
  }

  public Session getReviewerSessionIfInitialized() {
    return reviewerSession;
  }

  public void review() {
    ready = false;
    error = false;
    reviewerFeedbackText = "";
    reviewerDecisionReceived = false;

    Session activeReviewerSession = getOrCreateReviewerSession();
    if (activeReviewerSession == null) {
      error = true;
      logger.error("Reviewer session is unavailable; cannot run final review");
      return;
    }

    for (int iteration = 1; iteration <= maxReviewIterations && !ready && !error; iteration++) {
      long iterationStart = System.currentTimeMillis();
      boolean finalAttempt = iteration == maxReviewIterations;
      try {
        resetReviewerDecisionReceived();
        ResponseMessage message = requestReviewerDecision(iteration, finalAttempt);

        if (!consumeReviewerDecisionReceived()) {
          throw new LanguageModelException(
              "Reviewer must call output_feedback with ACCEPTED, REJECTED, or REVISION_REQUESTED");
        }

        if (ready) {
          logger.info("Reviewer accepted output; ending review loop");
          captureBenchmarkSnapshot(iteration, iterationStart, "REVIEW_ACCEPTED");
          return;
        }

        if (error) {
          logger.warn("Reviewer rejected output; ending review loop");
          captureBenchmarkSnapshot(iteration, iterationStart, "REVIEW_REJECTED");
          return;
        }

        if (finalAttempt) {
          throw new LanguageModelException(
              "Final review attempt must end in ACCEPTED or REJECTED; REVISION_REQUESTED is not allowed");
        }

        String reviewerFeedback = resolveReviewerFeedback(message);
        if (reviewerFeedback == null || reviewerFeedback.isBlank()) {
          throw new LanguageModelException("Reviewer requested revisions without feedback");
        }

        if (supervisor == null || !supervisor.handleReviewFeedback(reviewerFeedback)) {
          throw new LanguageModelException("Supervisor could not process reviewer feedback");
        }

        if (validator != null && validator.validate() == null) {
          logger.info("Review feedback handled successfully and output validated");
        }

        captureBenchmarkSnapshot(iteration, iterationStart, "REVIEW_ITERATION_" + iteration);
      } catch (Exception e) {
        logger.error("Error in review loop iteration {}: {}", iteration, e.getMessage());
        if (finalAttempt) {
          error = true;
          break;
        }
      }
    }

    if (!ready && !error) {
      error = true;
      logger.error(
          "Review stopped after {} iterations without a terminal decision", maxReviewIterations);
    }
  }

  private Session getOrCreateReviewerSession() {
    Session current = reviewerSession;
    if (current != null) {
      return current;
    }

    synchronized (this) {
      if (reviewerSession == null) {
        reviewerSession = reviewerSessionSupplier.get();
      }
      return reviewerSession;
    }
  }

  private void captureBenchmarkSnapshot(int iteration, long iterationStart, String stage) {
    if (benchmarkService == null || !benchmarkService.isEnabled()) {
      return;
    }

    try {
      long durationMs = System.currentTimeMillis() - iterationStart;
      int currentViolations = validator != null ? countViolations() : -1;
      int shapesProcessed = resolveShapesProcessed();

      benchmarkService.createBatchSnapshot(
          new DefaultBenchmarkSnapshotData(
              stage,
              iteration,
              shapesProcessed,
              durationMs,
              supervisor != null ? supervisor.supervisorSession() : null,
              reviewerSession,
              sharedTripleStore,
              List.of()),
          currentViolations);

      logger.debug(
          "Review iteration {} benchmark captured: stage={}, {} ms, {} violations",
          iteration,
          stage,
          durationMs,
          currentViolations);
    } catch (Exception e) {
      logger.debug("Failed to capture review iteration benchmark: {}", e.getMessage());
    }
  }

  private int resolveShapesProcessed() {
    if (supervisor == null
        || supervisor.shacl() == null
        || supervisor.shacl().getShapes() == null) {
      return 0;
    }

    return (int) supervisor.shacl().getShapes().stream().filter(Shacl.Shape::isProcessed).count();
  }

  private int countViolations() {
    try {
      String validationReport = validator.validate();
      if (validationReport == null || validationReport.isEmpty()) {
        return 0;
      }

      return (int) validationReport.lines().filter(line -> line.contains("sh:result")).count();
    } catch (Exception e) {
      logger.debug("Could not count violations: {}", e.getMessage());
      return -1;
    }
  }

  private ResponseMessage requestReviewerDecision(int iteration, boolean finalAttempt)
      throws Exception {
    Session activeReviewerSession = getOrCreateReviewerSession();
    if (activeReviewerSession == null) {
      throw new LanguageModelException("Reviewer session is unavailable");
    }

    String outputData = validator.getOutputDataAsString();
    OutputContext outputContext = new OutputContext(outputData);
    boolean changed = activeReviewerSession.addContextIfChanged(outputContext);

    String contextInfo = "";
    if (changed) {
      contextInfo =
          String.format(
              "\nNOTE: Updated output context (%d chars, %d lines).",
              outputData.length(), outputData.split("\\n").length);
    }

    String finalAttemptInstruction =
        finalAttempt
            ? " This is the final review attempt ("
                + iteration
                + "/"
                + maxReviewIterations
                + "). You MUST return a final decision: ACCEPTED or REJECTED."
                + " Do not return REVISION_REQUESTED on this attempt."
            : " Review attempt " + iteration + "/" + maxReviewIterations + ".";

    RequestMessage requestMessage =
        new RequestMessage(
            "Review the generated output against user instructions and ontology."
                + " Be pragmatic: request revisions only for blocking/high-impact issues;"
                + " accept if only minor non-blocking polish remains."
                + " Validate with shacl_validator(source=\"file\"); do not copy/retype the output's"
                + " Turtle content into a \"data\" argument, since retyping risks introducing"
                + " formatting mistakes that are not present in the actual file."
                + " You MUST call output_feedback exactly once with state=ACCEPTED,"
                + " REJECTED, or REVISION_REQUESTED, even if an earlier tool call in this turn"
                + " failed or errored — a tool failure is evidence for your decision, not a reason"
                + " to end the turn without calling output_feedback."
                + " If you choose REVISION_REQUESTED or REJECTED, include concise concrete"
                + " feedback (top 1-3 blockers) in output_feedback.feedback so the supervisor can apply it."
                + finalAttemptInstruction
                + contextInfo,
            "reviewer-review");

    try {
      return activeReviewerSession.prompt(requestMessage).get();
    } catch (Exception firstFailure) {
      if (!isSessionNotFoundError(firstFailure)) {
        throw firstFailure;
      }

      logger.warn("Reviewer session became stale; resetting and retrying review prompt once");
      activeReviewerSession.reset();
      return activeReviewerSession.prompt(requestMessage).get();
    }
  }

  private boolean isSessionNotFoundError(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && message.contains("Session not found")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  public void markReviewerDecisionReceived() {
    this.reviewerDecisionReceived = true;
  }

  private void resetReviewerDecisionReceived() {
    reviewerDecisionReceived = false;
  }

  private boolean consumeReviewerDecisionReceived() {
    boolean value = reviewerDecisionReceived;
    reviewerDecisionReceived = false;
    return value;
  }

  public void setReviewerFeedbackText(String reviewerFeedbackText) {
    this.reviewerFeedbackText = reviewerFeedbackText == null ? "" : reviewerFeedbackText.trim();
  }

  public String consumeReviewerFeedbackText() {
    String feedback = reviewerFeedbackText;
    reviewerFeedbackText = "";
    return feedback;
  }

  private String resolveReviewerFeedback(ResponseMessage message) {
    String toolFeedback = consumeReviewerFeedbackText();
    if (toolFeedback != null && !toolFeedback.isBlank()) {
      return toolFeedback;
    }

    return message == null ? "" : message.getMessage();
  }
}
