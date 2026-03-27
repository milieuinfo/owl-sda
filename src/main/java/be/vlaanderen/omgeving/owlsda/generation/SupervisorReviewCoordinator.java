package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.OutputContext;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.benchmark.DefaultBenchmarkSnapshotData;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates reviewer feedback and asks Supervisor to distribute tasks to workers.
 */
@Getter
public class SupervisorReviewCoordinator {
  private static final Logger logger = LoggerFactory.getLogger(SupervisorReviewCoordinator.class);

  private final Session reviewerSession;
  private final Supervisor supervisor;
  private final OutputValidator validator;
  private final BenchmarkService benchmarkService;
  private final WorkerTripleStore sharedTripleStore;

  @Setter
  private boolean ready = false;
  @Setter
  private boolean error = false;
  private volatile String reviewerFeedbackText = "";

  public SupervisorReviewCoordinator(Session reviewerSession, Supervisor supervisor, OutputValidator validator,
      BenchmarkService benchmarkService, WorkerTripleStore sharedTripleStore) {
    this.reviewerSession = reviewerSession;
    this.supervisor = supervisor;
    this.validator = validator;
    this.benchmarkService = benchmarkService;
    this.sharedTripleStore = sharedTripleStore;
  }

  public void review() {
    int fails = 0;
    int iteration = 0;
    while (!ready && !error) {
      try {
        iteration++;
        long iterationStart = System.currentTimeMillis();

        ResponseMessage message = requestReviewerDecision();

        if (shouldStopAfterReviewerDecision(message)) {
          captureBenchmarkSnapshot(iteration, iterationStart, true);
          continue;
        }

        String reviewerFeedback = resolveReviewerFeedback(message);
        if (reviewerFeedback == null || reviewerFeedback.isBlank()) {
          logger.warn("Reviewer requested revisions but provided no actionable feedback text");
          throw new IllegalStateException("Reviewer requested revisions without feedback");
        }

        boolean handled = supervisor.handleReviewFeedback(reviewerFeedback);
        if (handled && validator.validate() == null) {
          logger.info("Review feedback handled successfully and output validated");
        }

        captureBenchmarkSnapshot(iteration, iterationStart, false);
      } catch (Exception e) {
        logger.error("Error in review loop: {}", e.getMessage());
        fails++;
        if (fails > 5) {
          break;
        }
      }
    }
  }

  private void captureBenchmarkSnapshot(int iteration, long iterationStart, boolean accepted) {
    if (benchmarkService == null || !benchmarkService.isEnabled()) {
      return;
    }

    try {
      long durationMs = System.currentTimeMillis() - iterationStart;
      int currentViolations = validator != null ? countViolations() : -1;
      String iterationStage = accepted ? "REVIEW_ACCEPTED" : "REVIEW_ITERATION_" + iteration;
      int shapesProcessed = resolveShapesProcessed();

      benchmarkService.createBatchSnapshot(
          new DefaultBenchmarkSnapshotData(
              iterationStage,
              iteration,
              shapesProcessed,
              durationMs,
              supervisor != null ? supervisor.supervisorSession() : null,
              reviewerSession,
              sharedTripleStore,
              List.of()
          ),
          currentViolations
      );

      logger.debug("Review iteration {} benchmark captured: stage={}, {} ms, {} violations",
          iteration, iterationStage, durationMs, currentViolations);
    } catch (Exception e) {
      logger.debug("Failed to capture review iteration benchmark: {}", e.getMessage());
    }
  }

  private int resolveShapesProcessed() {
    if (supervisor == null || supervisor.shacl() == null || supervisor.shacl().getShapes() == null) {
      return 0;
    }

    return (int) supervisor.shacl().getShapes().stream()
        .filter(shape -> shape != null && shape.isProcessed())
        .count();
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

  private ResponseMessage requestReviewerDecision() throws Exception {
    String outputData = validator.getOutputDataAsString();
    OutputContext outputContext = new OutputContext(outputData);
    boolean changed = reviewerSession.addContextIfChanged(outputContext);

    String contextInfo = "";
    if (changed) {
      contextInfo = String.format(
          "\nNOTE: Updated output context (%d chars, %d lines).",
          outputData.length(), outputData.split("\\n").length);
    }

    return reviewerSession.prompt(new RequestMessage(
        "Review the generated output against user instructions and ontology."
            + " Be pragmatic: request revisions only for blocking/high-impact issues;"
            + " accept if only minor non-blocking polish remains."
            + " If you choose REVISION_REQUESTED or REJECTED, include concise concrete"
            + " feedback (top 1-3 blockers) in output_feedback.feedback so the supervisor can apply it."
            + contextInfo,
        "reviewer-review"
    )).get();
  }

  private boolean shouldStopAfterReviewerDecision(ResponseMessage message) {
    if (ready || error) {
      if (ready) {
        logger.info("Reviewer accepted output; ending review loop without sending final feedback to supervisor");
      }
      return true;
    }

    if (isAcceptedDecisionText(message.getMessage())) {
      // Fallback guard: if reviewer text clearly says ACCEPTED but tool state was not set,
      // treat it as accepted to avoid unnecessary supervisor rework.
      ready = true;
      error = false;
      logger.info("Reviewer decision text indicates ACCEPTED; ending review loop without sending final feedback to supervisor");
      return true;
    }

    return false;
  }

  private boolean isAcceptedDecisionText(String message) {
    if (message == null) {
      return false;
    }

    String normalized = message.trim().toUpperCase();
    if ("ACCEPTED".equals(normalized)) {
      return true;
    }

    return normalized.contains("DECISION: ACCEPTED")
        || normalized.contains("DECISION - ACCEPTED")
        || normalized.startsWith("ACCEPTED\n")
        || normalized.contains("\nACCEPTED\n");
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
