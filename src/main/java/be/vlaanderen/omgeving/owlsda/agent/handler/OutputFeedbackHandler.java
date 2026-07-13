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

  public static final String NAME = "output_feedback";

  private static final Logger logger = LoggerFactory.getLogger(OutputFeedbackHandler.class);

  public enum FeedbackState {
    ACCEPTED,
    REJECTED,
    REVISION_REQUESTED
  }

  @Override
  public String getName() {
    return NAME;
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
                "description", "The feedback state for the generated output"),
            "feedback", Map.of(
                "type", "string",
                "description", "Actionable reviewer feedback for supervisor revisions. Required when state is REVISION_REQUESTED or REJECTED.")
        ),
        "required", new String[]{"state"}
    );
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String stateStr = (String) arguments.get("state");
    if (stateStr == null || stateStr.isBlank()) {
      throw new IllegalArgumentException("output_feedback.state is required");
    }

    FeedbackState state;
    try {
      state = FeedbackState.valueOf(stateStr.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid output_feedback.state: " + stateStr, e);
    }

    String feedbackText = extractFeedbackText(arguments.get("feedback"));

    SupervisorReviewCoordinator coordinator = reviewCoordinatorProvider.get();
    if (coordinator != null) {
      handleFeedback(coordinator, state, feedbackText);
    }
    return CompletableFuture.completedFuture(null);
  }

  private void handleFeedback(SupervisorReviewCoordinator coordinator, FeedbackState state,
      String feedbackText) {
    coordinator.markReviewerDecisionReceived();

    switch (state) {
      case ACCEPTED:
        coordinator.setReady(true);
        coordinator.setError(false);
        coordinator.setReviewerFeedbackText("");
        logger.info("Output feedback: ACCEPTED (ready for use)");
        break;
      case REJECTED:
        requireActionableFeedback(state, feedbackText);
        coordinator.setReady(false);
        coordinator.setError(true);
        coordinator.setReviewerFeedbackText(feedbackText);
        logger.info("Output feedback: REJECTED (generation failed)");
        break;
      case REVISION_REQUESTED:
        requireActionableFeedback(state, feedbackText);
        coordinator.setReady(false);
        coordinator.setError(false);
        coordinator.setReviewerFeedbackText(feedbackText);
        logger.info("Output feedback: REVISION_REQUESTED (requires changes)");
        break;
    }
  }

  private void requireActionableFeedback(FeedbackState state, String feedbackText) {
    if (feedbackText == null || feedbackText.isBlank()) {
      throw new IllegalArgumentException(
          "output_feedback.feedback is required when state=" + state.name());
    }
  }

  private String extractFeedbackText(Object feedbackArgument) {
    if (feedbackArgument == null) {
      return "";
    }

    if (feedbackArgument instanceof String feedback) {
      return feedback.trim();
    }

    return feedbackArgument.toString().trim();
  }
}
