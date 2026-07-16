package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/** Tests for {@link WorkerResponsePublisher}. */
public class WorkerResponsePublisherTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Test
  public void publish_WorkerHasStructuredProgress_PublishesItToSupervisor() {
    RecordingSession worker = new RecordingSession();
    worker.addContext(context("Delegation Instructions", "do the work"));
    worker.addContext(context("Worker Progress Report", "status=CREATED\ntarget_shape=Widget"));

    SessionPool pool = new SessionPool(1);
    pool.addSession(worker);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.workerSessionPool()).thenReturn(pool);

    RecordingSession supervisor = new RecordingSession();

    new WorkerResponsePublisher(batch).publish(supervisor, List.of(worker));

    String published = contentOf(supervisor, "Worker Responses");
    assertTrue(published.contains("STRUCTURED_PROGRESS"));
    assertTrue(published.contains("target_shape=Widget"));
  }

  @Test
  public void publish_WorkerWithoutDelegationInstructions_IsSkipped() {
    RecordingSession worker = new RecordingSession();
    // No delegation instructions context at all.

    SessionPool pool = new SessionPool(1);
    pool.addSession(worker);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.workerSessionPool()).thenReturn(pool);

    RecordingSession supervisor = new RecordingSession();

    new WorkerResponsePublisher(batch).publish(supervisor, List.of(worker));

    assertNull(contentOf(supervisor, "Worker Responses"));
  }

  @Test
  public void publish_EmptyDelegatedList_DoesNothing() {
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    RecordingSession supervisor = new RecordingSession();

    new WorkerResponsePublisher(batch).publish(supervisor, List.of());

    assertNull(contentOf(supervisor, "Worker Responses"));
  }

  private static String contentOf(Session session, String name) {
    return session.getContext().stream()
        .filter(c -> name.equals(c.getName()))
        .findFirst()
        .map(Context::getContent)
        .orElse(null);
  }

  private static Context context(String name, String content) {
    Context context = new Context();
    context.setName(name);
    context.setType("text/plain");
    context.setContent(content);
    return context;
  }

  private static final class RecordingSession implements Session {
    private final List<Context> contexts = new ArrayList<>();

    @Override
    public void addContext(Context context) {
      contexts.removeIf(
          existing -> java.util.Objects.equals(existing.getName(), context.getName()));
      contexts.add(context);
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      addContext(context);
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.copyOf(contexts);
    }

    @Override
    public List<SessionMessageLogEntry> getMessageLog() {
      return List.of();
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
      return CompletableFuture.completedFuture(new ResponseMessage("id"));
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {}
  }
}
