package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SupervisorReviewCoordinatorFeedbackTest {

  @Test
  public void resolveReviewerFeedback_PrefersToolFeedbackWhenMessageIsEmpty() throws Exception {
    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(null, null, null, null, null);
    coordinator.setReviewerFeedbackText("Add ex:identifier for ex:Asset42");

    ResponseMessage message = new ResponseMessage("id-1");
    message.setMessage("");

    Method resolveMethod = SupervisorReviewCoordinator.class.getDeclaredMethod(
        "resolveReviewerFeedback",
        ResponseMessage.class
    );
    resolveMethod.setAccessible(true);

    String resolved = (String) resolveMethod.invoke(coordinator, message);

    assertEquals("Add ex:identifier for ex:Asset42", resolved);
    assertEquals("", coordinator.consumeReviewerFeedbackText());
  }
}

