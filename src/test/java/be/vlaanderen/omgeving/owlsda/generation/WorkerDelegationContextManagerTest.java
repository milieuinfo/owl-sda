package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/** Tests for {@link WorkerDelegationContextManager}. */
public class WorkerDelegationContextManagerTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Test
  public void getDelegatedWorkerCount_CountsOnlySessionsWithNonBlankDelegation() {
    RecordingSession delegated = new RecordingSession();
    delegated.addContext(context("Delegation Instructions", "do the work"));
    RecordingSession idle = new RecordingSession();
    idle.addContext(context("Delegation Instructions", ""));

    SessionPool pool = new SessionPool(2);
    pool.addSession(delegated);
    pool.addSession(idle);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.workerSessionPool()).thenReturn(pool);

    WorkerDelegationContextManager manager = new WorkerDelegationContextManager(batch);

    assertEquals(1, manager.getDelegatedWorkerCount());
    assertEquals(1, manager.getDelegatedWorkerSessions().size());
  }

  @Test
  public void getDelegatedWorkerCount_NullConcurrentWorkerBatch_ReturnsZero() {
    WorkerDelegationContextManager manager = new WorkerDelegationContextManager(null);

    assertEquals(0, manager.getDelegatedWorkerCount());
    assertTrue(manager.getDelegatedWorkerSessions().isEmpty());
  }

  @Test
  public void clearDelegationInstructions_BlanksDelegationAndProgressContexts() {
    RecordingSession session = new RecordingSession();
    session.addContext(context("Delegation Instructions", "do the work"));
    session.addContext(context("Worker Progress Report", "status=CREATED"));

    SessionPool pool = new SessionPool(1);
    pool.addSession(session);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.workerSessionPool()).thenReturn(pool);

    new WorkerDelegationContextManager(batch).clearDelegationInstructions();

    assertEquals("", contentOf(session, "Delegation Instructions"));
    assertEquals("", contentOf(session, "Worker Progress Report"));
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
      contexts.removeIf(existing -> java.util.Objects.equals(existing.getName(), context.getName()));
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
