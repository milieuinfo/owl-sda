package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OutputFeedbackHandlerTest {

  @Test
  public void handle_RevisionRequested_PersistsFeedbackForSupervisor() {
    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(null, null, null, null, null);
    OutputFeedbackHandler handler = new OutputFeedbackHandler(() -> coordinator);

    handler.handle(Map.of(
        "state", "REVISION_REQUESTED",
        "feedback", "Fix missing ex:identifier on ex:Asset42"
    )).join();

    assertFalse(coordinator.isReady());
    assertFalse(coordinator.isError());
    assertEquals("Fix missing ex:identifier on ex:Asset42", coordinator.consumeReviewerFeedbackText());
  }

  @Test
  public void handle_Accepted_ClearsBufferedFeedback() {
    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(null, null, null, null, null);
    coordinator.setReviewerFeedbackText("Stale feedback");
    OutputFeedbackHandler handler = new OutputFeedbackHandler(() -> coordinator);

    handler.handle(Map.of("state", "ACCEPTED")).join();

    assertTrue(coordinator.isReady());
    assertFalse(coordinator.isError());
    assertEquals("", coordinator.consumeReviewerFeedbackText());
  }

  @Test
  public void handle_MissingState_ThrowsError() {
    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(null, null, null, null, null);
    OutputFeedbackHandler handler = new OutputFeedbackHandler(() -> coordinator);

    try {
      handler.handle(Map.of("feedback", "some feedback"));
      fail("Expected IllegalArgumentException for missing state");
    } catch (IllegalArgumentException e) {
      assertEquals("output_feedback.state is required", e.getMessage());
    }
  }

  @Test
  public void handle_RevisionRequestedWithoutFeedback_ThrowsError() {
    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(null, null, null, null, null);
    OutputFeedbackHandler handler = new OutputFeedbackHandler(() -> coordinator);

    try {
      handler.handle(Map.of("state", "REVISION_REQUESTED")).join();
      fail("Expected IllegalArgumentException for missing feedback");
    } catch (IllegalArgumentException e) {
      assertEquals("output_feedback.feedback is required when state=REVISION_REQUESTED", e.getMessage());
    }
  }
}
