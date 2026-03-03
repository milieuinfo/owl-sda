package be.vlaanderen.omgeving.owlsda.agent.handler;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles output feedback from the LLM, supporting multiple feedback states: ACCEPTED (output is
 * ready), REJECTED (generation failed), or REVISION_REQUESTED.
 */
public record OutputFeedbackHandler(
    Supplier<SupervisorReviewCoordinator> reviewCoordinatorProvider) implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(OutputFeedbackHandler.class);

  public enum FeedbackState {
    ACCEPTED,
    REJECTED,
    REVISION_REQUESTED
  }

  @Override
  public String getName() {
    return "output_feedback";
  }

  @Override
  public String getDescription() {
    return "Provide feedback on output generation (ACCEPTED, REJECTED, or REVISION_REQUESTED).";
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "state", Map.of(
                "type", "string",
                "enum", new String[]{"ACCEPTED", "REJECTED", "REVISION_REQUESTED"},
                "description", "The feedback state for the generated output")
        ),
        "required", new String[]{"state"}
    );
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String stateStr = (String) arguments.get("state");
    if (stateStr == null) {
      logger.warn("No feedback state provided, defaulting to REVISION_REQUESTED");
      stateStr = FeedbackState.REVISION_REQUESTED.toString();
    }
    FeedbackState state;
    try {
      state = FeedbackState.valueOf(stateStr);
    } catch (IllegalArgumentException e) {
      logger.warn("Invalid feedback state: {}. Defaulting to REVISION_REQUESTED", stateStr);
      state = FeedbackState.REVISION_REQUESTED;
    }
    SupervisorReviewCoordinator coordinator = reviewCoordinatorProvider.get();
    if (coordinator != null) {
      handleFeedback(coordinator, state);
    }
    return CompletableFuture.completedFuture(null);
  }

  private void handleFeedback(SupervisorReviewCoordinator coordinator, FeedbackState state) {
    switch (state) {
      case ACCEPTED:
        coordinator.setReady(true);
        coordinator.setError(false);
        logger.info("Output feedback: ACCEPTED (ready for use)");
        break;
      case REJECTED:
        coordinator.setReady(false);
        coordinator.setError(true);
        logger.info("Output feedback: REJECTED (generation failed)");
        break;
      case REVISION_REQUESTED:
        coordinator.setReady(false);
        coordinator.setError(false);
        logger.info("Output feedback: REVISION_REQUESTED (requires changes)");
        break;
    }
  }
}
