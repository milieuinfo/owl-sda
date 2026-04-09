package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

  @Test
  public void review_StopsAfterThreeIterationsWithoutReviewerDecision() {
    CountingSession reviewerSession = new CountingSession("Please revise");
    StubOutputValidator validator = new StubOutputValidator();

    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(
        reviewerSession,
        null,
        validator,
        null,
        null
    );

    coordinator.review();

    assertFalse(coordinator.isReady());
    assertTrue(coordinator.isError());
    assertEquals(3, reviewerSession.getPromptCount());
  }

  @Test
  public void review_UsesConfiguredMaxIterations() {
    CountingSession reviewerSession = new CountingSession("Please revise");
    StubOutputValidator validator = new StubOutputValidator();

    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(
        reviewerSession,
        null,
        validator,
        null,
        null,
        5
    );

    coordinator.review();

    assertFalse(coordinator.isReady());
    assertTrue(coordinator.isError());
    assertEquals(5, reviewerSession.getPromptCount());
  }

  @Test
  public void review_FinalAttemptPromptRequiresTerminalDecision() {
    CountingSession reviewerSession = new CountingSession("Please revise");
    StubOutputValidator validator = new StubOutputValidator();

    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(
        reviewerSession,
        null,
        validator,
        null,
        null,
        2
    );

    coordinator.review();

    assertEquals(2, reviewerSession.getPromptCount());
    assertTrue(reviewerSession.getLastPromptMessage().contains("final review attempt (2/2)"));
    assertTrue(reviewerSession.getLastPromptMessage().contains("ACCEPTED or REJECTED"));
  }

  @Test
  public void review_CreatesReviewerSessionOnlyWhenReviewStarts() {
    AtomicInteger supplierCalls = new AtomicInteger(0);
    CountingSession reviewerSession = new CountingSession("Please revise");
    StubOutputValidator validator = new StubOutputValidator();

    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(
        () -> {
          supplierCalls.incrementAndGet();
          return reviewerSession;
        },
        null,
        validator,
        null,
        null,
        2
    );

    assertEquals(0, supplierCalls.get());
    assertEquals(null, coordinator.getReviewerSessionIfInitialized());

    coordinator.review();

    assertEquals(1, supplierCalls.get());
    assertEquals(2, reviewerSession.getPromptCount());
  }

  @Test
  public void review_RetriesOnceAfterSessionNotFoundByResettingSession() {
    FlakySession reviewerSession = new FlakySession("Please revise");
    StubOutputValidator validator = new StubOutputValidator();

    SupervisorReviewCoordinator coordinator = new SupervisorReviewCoordinator(
        reviewerSession,
        null,
        validator,
        null,
        null,
        2
    );

    coordinator.review();

    assertEquals(2, reviewerSession.getPromptCount());
    assertEquals(1, reviewerSession.getResetCount());
  }

  private static final class CountingSession implements Session {
    private final ResponseMessage responseMessage;
    private int promptCount;
    private String lastPromptMessage = "";

    private CountingSession(String message) {
      this.responseMessage = new ResponseMessage("test-response");
      this.responseMessage.setMessage(message);
    }

    int getPromptCount() {
      return promptCount;
    }

    String getLastPromptMessage() {
      return lastPromptMessage;
    }

    @Override
    public void addContext(Context context) {
      // no-op for test
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.of();
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      return prompt(input);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      promptCount++;
      lastPromptMessage = input.getMessage();
      return CompletableFuture.completedFuture(responseMessage);
    }

    @Override
    public List<SessionMessageLogEntry> getMessageLog() {
      return List.of();
    }

    @Override
    public void close() {
      // no-op for test
    }
  }

  private static final class FlakySession extends CountingSession {
    private int resetCount;
    private boolean failFirstPrompt = true;

    private FlakySession(String message) {
      super(message);
    }

    int getResetCount() {
      return resetCount;
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      if (failFirstPrompt) {
        failFirstPrompt = false;
        CompletableFuture<ResponseMessage> failed = new CompletableFuture<>();
        failed.completeExceptionally(new ExecutionException(
            new RuntimeException("Session not found: stale-session")));
        return failed;
      }
      return super.prompt(input);
    }

    @Override
    public void reset() {
      resetCount++;
    }
  }

  private static final class StubOutputValidator extends OutputValidator {
    private StubOutputValidator() {
      super(null, null);
    }

    @Override
    public String getOutputDataAsString() {
      return "@prefix ex: <http://example.org/> .\nex:s ex:p ex:o .";
    }

    @Override
    public String validate() {
      return null;
    }
  }
}
