package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}

