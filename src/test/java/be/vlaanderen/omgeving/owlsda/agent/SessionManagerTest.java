package be.vlaanderen.omgeving.owlsda.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SessionManager}, focused on the parts reachable without a live LLM client:
 * delegation-target routing ({@code publishDelegationContext}/{@code normalizeTargetAgent}),
 * broadcasting context to every known session, and shutdown robustness. The worker pool /
 * supervisor / reviewer sessions have no public setters (only {@link SessionManager#initialize()}
 * populates them, which requires a real {@link Client}), so tests reach into the private fields via
 * reflection to install {@link FakeSession} doubles directly - mirroring the reflection-based
 * private-method testing pattern already used for {@code Supervisor} in
 * WorkerAgentDelegationPromptTest.
 */
public class SessionManagerTest {

  private SessionManager manager;

  @Before
  public void setUp() {
    manager = new SessionManager(new Config());
  }

  // ---------------------------------------------------------------------
  // publishDelegationContext / normalizeTargetAgent routing
  // ---------------------------------------------------------------------

  @Test
  public void publishDelegationContext_PoolNotation_RoutesToMatchingWorker() throws Exception {
    FakeSession worker0 = new FakeSession();
    FakeSession worker1 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0, worker1));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);

    DelegationHandler.PublicationResult result =
        publish(manager, "Round instructions", "POOL-1", "do the pool-1 work");

    assertTrue(result.success());
    assertEquals("POOL-1", result.resolvedTargetAgent());
    assertEquals("do the pool-1 work", contentOf(worker1, "Round instructions"));
    assertEquals(
        "do the pool-1 work", contentOf(worker1, DelegationHandler.DELEGATION_CONTEXT_NAME));
    assertTrue("untargeted worker must not receive instructions", worker0.contexts.isEmpty());
    assertEquals("do the pool-1 work", contentOf(supervisorSession, "Round instructions [POOL-1]"));
  }

  @Test
  public void publishDelegationContext_WorkerNotation_NormalizesToPoolIndex() throws Exception {
    FakeSession worker0 = new FakeSession();
    FakeSession worker1 = new FakeSession();
    FakeSession worker2 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0, worker1, worker2));

    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "WORKER-2", "fix violations");

    assertTrue(result.success());
    assertEquals("POOL-2", result.resolvedTargetAgent());
    assertEquals("fix violations", contentOf(worker2, DelegationHandler.DELEGATION_CONTEXT_NAME));
    assertTrue(worker0.contexts.isEmpty());
    assertTrue(worker1.contexts.isEmpty());
  }

  @Test
  public void publishDelegationContext_BareIndexNotation_NormalizesToPoolIndex() throws Exception {
    FakeSession worker0 = new FakeSession();
    FakeSession worker1 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0, worker1));

    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "1", "generate instances");

    assertTrue(result.success());
    assertEquals("POOL-1", result.resolvedTargetAgent());
    assertEquals(
        "generate instances", contentOf(worker1, DelegationHandler.DELEGATION_CONTEXT_NAME));
  }

  @Test
  public void publishDelegationContext_BareIndexIsCaseAndWhitespaceTolerant() throws Exception {
    FakeSession worker0 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0));

    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "  pool-0  ", "do work");

    assertTrue(result.success());
    assertEquals("POOL-0", result.resolvedTargetAgent());
  }

  @Test
  public void publishDelegationContext_UnknownTargetAgent_ReturnsError() throws Exception {
    setField(manager, "workerSessionPool", poolOf(new FakeSession()));

    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "POOL-99", "do work");

    assertFalse(result.success());
    assertTrue(result.message().contains("Unknown target_agent"));
    assertNull(result.resolvedTargetAgent());
  }

  @Test
  public void publishDelegationContext_GarbageTargetAgent_ReturnsError() throws Exception {
    setField(manager, "workerSessionPool", poolOf(new FakeSession()));

    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "not-a-worker-id", "do work");

    assertFalse(result.success());
    assertTrue(result.message().contains("Unknown target_agent"));
  }

  @Test
  public void publishDelegationContext_NullWorkerPool_ReturnsError() throws Exception {
    // workerSessionPool left null (never initialized).
    DelegationHandler.PublicationResult result =
        publish(manager, "Delegation Instructions", "POOL-0", "do work");

    assertFalse(result.success());
    assertEquals("Worker session pool is not initialized", result.message());
  }

  // ---------------------------------------------------------------------
  // addContextToAllSessions / addContextToAllSessionsIfChanged
  // ---------------------------------------------------------------------

  @Test
  public void addContextToAllSessions_ReachesWorkerSupervisorAndReviewerSessions()
      throws Exception {
    FakeSession worker0 = new FakeSession();
    FakeSession worker1 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0, worker1));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);
    FakeSession reviewerSession = new FakeSession();
    setField(manager, "reviewerSession", reviewerSession);

    Context context = new Context();
    context.setName("Shared Ontology");
    context.setType("text/turtle");
    context.setContent("ex:Thing a owl:Class .");

    manager.addContextToAllSessions(context);

    assertEquals("ex:Thing a owl:Class .", contentOf(worker0, "Shared Ontology"));
    assertEquals("ex:Thing a owl:Class .", contentOf(worker1, "Shared Ontology"));
    assertEquals("ex:Thing a owl:Class .", contentOf(supervisorSession, "Shared Ontology"));
    assertEquals("ex:Thing a owl:Class .", contentOf(reviewerSession, "Shared Ontology"));
  }

  @Test
  public void addContextToAllSessions_ReviewerNotYetInitialized_SkipsReviewerWithoutError()
      throws Exception {
    FakeSession worker0 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);
    // reviewerSession left null - simulates a run where review has not started yet.

    Context context = new Context();
    context.setName("Validation Report");
    context.setContent("no violations");

    manager.addContextToAllSessions(context);

    assertEquals("no violations", contentOf(worker0, "Validation Report"));
    assertEquals("no violations", contentOf(supervisorSession, "Validation Report"));
    assertNull(manager.getReviewerSessionIfInitialized());
  }

  @Test
  public void addContextToAllSessionsIfChanged_ReachesAllInitializedSessions() throws Exception {
    FakeSession worker0 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);
    FakeSession reviewerSession = new FakeSession();
    setField(manager, "reviewerSession", reviewerSession);

    Context context = new Context();
    context.setName("Worker Progress Report");
    context.setContent("status=CREATED");

    manager.addContextToAllSessionsIfChanged(context);

    assertEquals("status=CREATED", contentOf(worker0, "Worker Progress Report"));
    assertEquals("status=CREATED", contentOf(supervisorSession, "Worker Progress Report"));
    assertEquals("status=CREATED", contentOf(reviewerSession, "Worker Progress Report"));
  }

  // ---------------------------------------------------------------------
  // addContextToSupervisorAndReviewer
  // ---------------------------------------------------------------------

  @Test
  public void addContextToSupervisorAndReviewer_SkipsWorkerSessions() throws Exception {
    FakeSession worker0 = new FakeSession();
    FakeSession worker1 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0, worker1));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);
    FakeSession reviewerSession = new FakeSession();
    setField(manager, "reviewerSession", reviewerSession);

    Context context = new Context();
    context.setName("Ontology");
    context.setType("text/turtle");
    context.setContent("ex:Thing a owl:Class .");

    manager.addContextToSupervisorAndReviewer(context);

    assertEquals("ex:Thing a owl:Class .", contentOf(supervisorSession, "Ontology"));
    assertEquals("ex:Thing a owl:Class .", contentOf(reviewerSession, "Ontology"));
    assertTrue("workers must not receive the context", worker0.contexts.isEmpty());
    assertTrue("workers must not receive the context", worker1.contexts.isEmpty());
  }

  @Test
  public void
      addContextToSupervisorAndReviewer_ReviewerNotYetInitialized_SkipsReviewerWithoutError()
          throws Exception {
    FakeSession worker0 = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(worker0));
    FakeSession supervisorSession = new FakeSession();
    setField(manager, "supervisorSession", supervisorSession);
    // reviewerSession left null - simulates a run where review has not started yet.

    Context context = new Context();
    context.setName("Ontology");
    context.setContent("ex:Thing a owl:Class .");

    manager.addContextToSupervisorAndReviewer(context);

    assertEquals("ex:Thing a owl:Class .", contentOf(supervisorSession, "Ontology"));
    assertTrue(worker0.contexts.isEmpty());
    assertNull(manager.getReviewerSessionIfInitialized());
  }

  // ---------------------------------------------------------------------
  // shutdown()
  // ---------------------------------------------------------------------

  @Test
  public void shutdown_ClosesAllSessionsAndClients_EvenWhenSessionCloseThrows() throws Exception {
    FakeSession throwingWorker = new FakeSession();
    throwingWorker.closeThrows = true;
    FakeSession healthyWorker = new FakeSession();
    setField(manager, "workerSessionPool", poolOf(throwingWorker, healthyWorker));

    FakeSession supervisorSession = new FakeSession();
    supervisorSession.closeThrows = true;
    setField(manager, "supervisorSession", supervisorSession);

    FakeSession reviewerSession = new FakeSession();
    setField(manager, "reviewerSession", reviewerSession);

    FakeClient throwingClient = new FakeClient();
    throwingClient.closeThrows = true;
    @SuppressWarnings("unchecked")
    Map<String, Client> clientsByProvider =
        (Map<String, Client>) getField(manager, "clientsByProvider");
    clientsByProvider.put("fake-provider", throwingClient);

    // Must not throw despite every collaborator failing to close cleanly.
    manager.shutdown();

    assertTrue(throwingWorker.closeAttempted);
    assertTrue(healthyWorker.closeAttempted);
    assertTrue(supervisorSession.closeAttempted);
    assertTrue(reviewerSession.closeAttempted);
    assertTrue(throwingClient.closeAttempted);
  }

  @Test
  public void shutdown_NoSessionsInitialized_DoesNotThrow() {
    // workerSessionPool/supervisorSession/reviewerSession all left null.
    manager.shutdown();
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private static SessionPool poolOf(Session... sessions) {
    SessionPool pool = new SessionPool(sessions.length);
    for (Session session : sessions) {
      pool.addSession(session);
    }
    return pool;
  }

  private static String contentOf(FakeSession session, String contextName) {
    for (Context context : session.contexts) {
      if (contextName.equals(context.getName())) {
        return context.getContent();
      }
    }
    return null;
  }

  private static DelegationHandler.PublicationResult publish(
      SessionManager manager, String name, String targetAgent, String instructions)
      throws Exception {
    Method method =
        SessionManager.class.getDeclaredMethod(
            "publishDelegationContext", String.class, String.class, String.class);
    method.setAccessible(true);
    return (DelegationHandler.PublicationResult)
        method.invoke(manager, name, targetAgent, instructions);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = SessionManager.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = SessionManager.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  /** Minimal in-memory {@link Session} double tracking added contexts and close() attempts. */
  private static final class FakeSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private boolean closeThrows = false;
    private boolean closeAttempted = false;

    @Override
    public void addContext(Context context) {
      contexts.removeIf(existing -> sameName(existing, context));
      contexts.add(new Context(context));
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      Context existing =
          contexts.stream()
              .filter(candidate -> sameName(candidate, context))
              .findFirst()
              .orElse(null);
      if (existing != null
          && java.util.Objects.equals(existing.getContent(), context.getContent())) {
        return false;
      }
      addContext(context);
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.copyOf(contexts);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
      ResponseMessage response = new ResponseMessage("test-id");
      response.setMessage("ok");
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {
      closeAttempted = true;
      if (closeThrows) {
        throw new RuntimeException("simulated close failure");
      }
    }

    private boolean sameName(Context left, Context right) {
      return java.util.Objects.equals(left.getName(), right.getName());
    }
  }

  /** Minimal {@link Client} double used only to exercise shutdown()'s client-closing loop. */
  private static final class FakeClient implements Client {
    private boolean closeThrows = false;
    private boolean closeAttempted = false;

    @Override
    public String getName() {
      return "fake";
    }

    @Override
    public Session createSession(SessionConfig config) {
      throw new UnsupportedOperationException("not needed for this test");
    }

    @Override
    public List<Session> getSessions() {
      return List.of();
    }

    @Override
    public void close() throws Exception {
      closeAttempted = true;
      if (closeThrows) {
        throw new Exception("simulated client close failure");
      }
    }
  }
}
