package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Tests for {@link Supervisor}: the top-level {@code orchestrate()} guard clauses, a full
 * delegation round driven end-to-end through a fake supervisor session and a mocked {@link
 * ConcurrentWorkerBatch}, the worker-progress-derived completion helpers ({@code
 * isAcceptableWorkerStatus}/{@code isConformingProgress}), and {@code
 * reopenShapesWithOutstandingViolations}. Follows the reflection-based private-method testing
 * pattern established in {@code WorkerAgentDelegationPromptTest}: real records are constructed with
 * mostly-null collaborators and private methods are invoked via {@code setAccessible(true)}.
 */
public class SupervisorTest {

  static {
    // Java 25 is newer than what the ByteBuddy version bundled with Mockito 5.14.x officially
    // supports; without this flag Mockito's inline mock maker (needed to mock the concrete
    // ConcurrentWorkerBatch/OutputValidator collaborators below) fails to instrument classes on
    // this JDK. Must be set before the first Mockito.mock() call in this JVM.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final String NS = "http://example.org/";

  // ---------------------------------------------------------------------
  // orchestrate() guard clauses
  // ---------------------------------------------------------------------

  @Test
  public void orchestrate_NullConcurrentWorkerBatch_ReturnsFalse() {
    Supervisor supervisor =
        new Supervisor(new NoOpSession(), mock(OutputValidator.class), null, null, null, null);

    assertFalse(supervisor.orchestrate(1, true));
  }

  @Test
  public void orchestrate_NullValidator_ReturnsFalse() {
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    Supervisor supervisor = new Supervisor(new NoOpSession(), null, batch, null, null, null);

    assertFalse(supervisor.orchestrate(1, true));
  }

  @Test
  public void orchestrate_ExceptionDuringDelegation_ReturnsFalseInsteadOfThrowing() {
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenThrow(new RuntimeException("boom"));

    Shacl shacl = shaclWithClasses("Widget");
    Supervisor supervisor =
        new Supervisor(new NoOpSession(), mock(OutputValidator.class), batch, shacl, null, null);

    // getWorkerCount() is invoked while building delegation instructions; the failure must be
    // swallowed by orchestrate()'s try/catch rather than propagate.
    assertFalse(supervisor.orchestrate(1, true));
  }

  // ---------------------------------------------------------------------
  // Full delegation round via delegate()/executeDelegatedRound()
  // ---------------------------------------------------------------------

  @Test
  public void orchestrate_SupervisorNeverDelegates_ReturnsFalseAfterRetry() throws Exception {
    Shacl shacl = shaclWithClasses("Widget");
    SessionPool workerPool = poolOf(new FakeWorkerSession());

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);

    AtomicInteger promptCount = new AtomicInteger();
    Session silentSupervisorSession = new CountingNoOpSession(promptCount);

    Supervisor supervisor =
        new Supervisor(
            silentSupervisorSession, mock(OutputValidator.class), batch, shacl, null, null);

    boolean result = supervisor.orchestrate(1, true);

    assertFalse(result);
    // First attempt + strict retry attempt = 2 prompts, and never a delegated worker.
    assertEquals(2, promptCount.get());
    assertEquals(0, supervisor.getDelegatedWorkerCount());
  }

  @Test
  public void orchestrate_WorkerReportsConformingProgress_MarksShapeProcessed() throws Exception {
    Shacl shacl = shaclWithClasses("Widget");
    String shapeName = shacl.getShapes().get(0).getName();

    FakeWorkerSession workerSession = new FakeWorkerSession();
    SessionPool workerPool = poolOf(workerSession);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);
    when(batch.runWithDelegation(anyString(), anyInt())).thenReturn(true);

    String progressReport =
        String.join(
            "\n",
            "worker_id=POOL-0",
            "status=CREATED",
            "target_shape=" + shapeName,
            "target_class=:Widget",
            "changed_triples_count=3",
            "created_or_updated_subjects=:w1",
            "validation_result=CONFORMS",
            "remaining_issues=none");

    DelegatingSupervisorSession supervisorSession =
        new DelegatingSupervisorSession(workerPool, progressReport);

    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);
    Supervisor supervisor =
        new Supervisor(supervisorSession, mock(OutputValidator.class), batch, shacl, null, tracker);

    boolean result = supervisor.orchestrate(1, true);

    assertTrue(result);
    assertTrue(shacl.getShapes().get(0).isProcessed());
    assertEquals(1, tracker.getStatus().getCompletedCount());
  }

  @Test
  public void orchestrate_BatchFailsButWorkerProgressConforms_StillReturnsTrue() throws Exception {
    Shacl shacl = shaclWithClasses("Widget");
    String shapeName = shacl.getShapes().get(0).getName();

    FakeWorkerSession workerSession = new FakeWorkerSession();
    SessionPool workerPool = poolOf(workerSession);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);
    // Batch itself reports failure (e.g. a timeout), but the worker still filed valid progress.
    when(batch.runWithDelegation(anyString(), anyInt())).thenReturn(false);

    String progressReport =
        String.join(
            "\n",
            "worker_id=POOL-0",
            "status=VERIFIED_NO_CHANGE",
            "target_shape=" + shapeName,
            "target_class=:Widget",
            "changed_triples_count=0",
            "created_or_updated_subjects=none",
            "validation_result=CONFORMS",
            "remaining_issues=none");

    DelegatingSupervisorSession supervisorSession =
        new DelegatingSupervisorSession(workerPool, progressReport);

    Supervisor supervisor =
        new Supervisor(supervisorSession, mock(OutputValidator.class), batch, shacl, null, null);

    boolean result = supervisor.orchestrate(1, true);

    assertTrue("progress-marked shapes must redeem an otherwise-failed batch", result);
    assertTrue(shacl.getShapes().get(0).isProcessed());
  }

  @Test
  public void orchestrate_WorkerReportsBlockedStatus_DoesNotMarkShapeProcessed() throws Exception {
    Shacl shacl = shaclWithClasses("Widget");
    String shapeName = shacl.getShapes().get(0).getName();

    FakeWorkerSession workerSession = new FakeWorkerSession();
    SessionPool workerPool = poolOf(workerSession);

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);
    when(batch.runWithDelegation(anyString(), anyInt())).thenReturn(false);

    String progressReport =
        String.join(
            "\n",
            "worker_id=POOL-0",
            "status=BLOCKED",
            "target_shape=" + shapeName,
            "target_class=:Widget",
            "changed_triples_count=0",
            "created_or_updated_subjects=none",
            "validation_result=NON_CONFORMS",
            "remaining_issues=cannot resolve dependency");

    DelegatingSupervisorSession supervisorSession =
        new DelegatingSupervisorSession(workerPool, progressReport);

    Supervisor supervisor =
        new Supervisor(supervisorSession, mock(OutputValidator.class), batch, shacl, null, null);

    boolean result = supervisor.orchestrate(1, true);

    assertFalse(result);
    assertFalse(shacl.getShapes().get(0).isProcessed());
  }

  // ---------------------------------------------------------------------
  // isAcceptableWorkerStatus / isConformingProgress (private helpers)
  // ---------------------------------------------------------------------

  @Test
  public void isAcceptableWorkerStatus_AcceptsOnlyNonBlockedKnownStatuses() throws Exception {
    Method method = Supervisor.class.getDeclaredMethod("isAcceptableWorkerStatus", String.class);
    method.setAccessible(true);

    assertTrue((Boolean) method.invoke(null, "CREATED"));
    assertTrue((Boolean) method.invoke(null, "FIXED"));
    assertTrue((Boolean) method.invoke(null, "VERIFIED_NO_CHANGE"));
    assertFalse((Boolean) method.invoke(null, "BLOCKED"));
    assertFalse((Boolean) method.invoke(null, "NOT_A_REAL_STATUS"));
    assertFalse((Boolean) method.invoke(null, ""));
  }

  @Test
  public void isConformingProgress_RequiresAcceptableStatusAndConformsResult() throws Exception {
    Supervisor supervisor = new Supervisor(null, null, null, null, null, null);
    Method method = Supervisor.class.getDeclaredMethod("isConformingProgress", Map.class);
    method.setAccessible(true);

    assertTrue((Boolean) method.invoke(supervisor, progressMap("CREATED", "CONFORMS")));
    assertFalse(
        "non-conforming validation result must not count as progress",
        (Boolean) method.invoke(supervisor, progressMap("CREATED", "NON_CONFORMS")));
    assertFalse(
        "BLOCKED status must never count as progress even if flagged CONFORMS",
        (Boolean) method.invoke(supervisor, progressMap("BLOCKED", "CONFORMS")));
    assertFalse((Boolean) method.invoke(supervisor, new HashMap<String, String>()));
  }

  private Map<String, String> progressMap(String status, String validationResult) {
    Map<String, String> map = new HashMap<>();
    map.put("status", status);
    map.put("validation_result", validationResult);
    return map;
  }

  // ---------------------------------------------------------------------
  // reopenShapesWithOutstandingViolations
  // ---------------------------------------------------------------------

  @Test
  public void reopenShapesWithOutstandingViolations_ReopensProcessedShapeWithActiveViolation()
      throws Exception {
    Shacl shacl = shaclWithHasNameRestriction();
    Shacl.Shape shape = shacl.getShapes().get(0);
    shape.setProcessed(true);

    WorkerTripleStore store = new WorkerTripleStore(null);
    // ex:p1 a ex:Person . (missing required ex:hasName -> violates minCount 1)
    store.addTriples("<" + NS + "p1> <" + RDF.type.getURI() + "> <" + NS + "Person> .", "TEST");

    Supervisor supervisor = new Supervisor(null, null, null, shacl, store, null);
    invokeVoid(supervisor, "reopenShapesWithOutstandingViolations");

    assertFalse("shape with an active violation must be reopened", shape.isProcessed());
  }

  @Test
  public void reopenShapesWithOutstandingViolations_LeavesConformingShapeProcessed()
      throws Exception {
    Shacl shacl = shaclWithHasNameRestriction();
    Shacl.Shape shape = shacl.getShapes().get(0);
    shape.setProcessed(true);

    WorkerTripleStore store = new WorkerTripleStore(null);
    // Fully conforming instance: has the required ex:hasName literal.
    store.addTriples(
        "<"
            + NS
            + "p1> <"
            + RDF.type.getURI()
            + "> <"
            + NS
            + "Person> .\n<"
            + NS
            + "p1> <"
            + NS
            + "hasName> \"Alice\" .",
        "TEST");

    Supervisor supervisor = new Supervisor(null, null, null, shacl, store, null);
    invokeVoid(supervisor, "reopenShapesWithOutstandingViolations");

    assertTrue("conforming shape must remain processed", shape.isProcessed());
  }

  @Test
  public void reopenShapesWithOutstandingViolations_EmptyStore_DoesNothing() throws Exception {
    Shacl shacl = shaclWithHasNameRestriction();
    Shacl.Shape shape = shacl.getShapes().get(0);
    shape.setProcessed(true);

    WorkerTripleStore emptyStore = new WorkerTripleStore(null);
    Supervisor supervisor = new Supervisor(null, null, null, shacl, emptyStore, null);
    invokeVoid(supervisor, "reopenShapesWithOutstandingViolations");

    assertTrue(shape.isProcessed());
  }

  // ---------------------------------------------------------------------
  // Fixtures / helpers
  // ---------------------------------------------------------------------

  private static Shacl shaclWithClasses(String... localNames) {
    Model ontology = ModelFactory.createDefaultModel();
    ontology.setNsPrefix("ex", NS);
    for (String localName : localNames) {
      Resource cls = ontology.createResource(NS + localName);
      cls.addProperty(RDF.type, OWL.Class);
    }
    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();
    return shacl;
  }

  /** Person class with a required (minCount 1) ex:hasName datatype property. */
  private static Shacl shaclWithHasNameRestriction() {
    String ontologyTtl =
        "@prefix ex: <"
            + NS
            + "> .\n"
            + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
            + "ex:Person a owl:Class ;\n"
            + "  rdfs:subClassOf [\n"
            + "    a owl:Restriction ;\n"
            + "    owl:onProperty ex:hasName ;\n"
            + "    owl:allValuesFrom xsd:string ;\n"
            + "    owl:minCardinality 1\n"
            + "  ] .\n";
    Model ontologyModel = ModelFactory.createDefaultModel();
    ontologyModel.read(new StringReader(ontologyTtl), null, "TURTLE");
    Shacl shacl = new Shacl(ontologyModel);
    shacl.generate();
    return shacl;
  }

  private static SessionPool poolOf(Session... sessions) {
    SessionPool pool = new SessionPool(sessions.length);
    for (Session session : sessions) {
      pool.addSession(session);
    }
    return pool;
  }

  private static void invokeVoid(Object target, String methodName) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(target);
  }

  /** A minimal worker-pool session double supporting context add/read, nothing else. */
  private static final class FakeWorkerSession implements Session {
    final List<Context> contexts = new ArrayList<>();

    @Override
    public void addContext(Context context) {
      contexts.removeIf(existing -> sameName(existing, context));
      contexts.add(new Context(context));
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
      ResponseMessage response = new ResponseMessage("worker-id");
      response.setMessage("ok");
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {}

    private boolean sameName(Context left, Context right) {
      return java.util.Objects.equals(left.getName(), right.getName());
    }
  }

  /** Supervisor session double that never delegates to any worker, no matter how many prompts. */
  private static class NoOpSession implements Session {
    private final List<Context> contexts = new ArrayList<>();

    @Override
    public void addContext(Context context) {
      contexts.add(context);
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      contexts.add(context);
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.copyOf(contexts);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
      ResponseMessage response = new ResponseMessage("supervisor-id");
      response.setMessage("ok, no delegation");
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {}
  }

  /** Same as {@link NoOpSession} but counts every prompt() invocation. */
  private static final class CountingNoOpSession extends NoOpSession {
    private final AtomicInteger promptCount;

    CountingNoOpSession(AtomicInteger promptCount) {
      this.promptCount = promptCount;
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
      promptCount.incrementAndGet();
      return super.prompt(input, ctxs);
    }
  }

  /**
   * Supervisor session double that simulates the supervisor actually delegating: every prompt()
   * marks every worker session in {@code workerPool} as having received delegation instructions
   * (mirroring what {@code SessionManager#publishDelegationContext} would do in production), and
   * optionally seeds a structured worker-progress report so {@code
   * markCompletedShapesFromWorkerProgress} has something to read.
   */
  private static final class DelegatingSupervisorSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private final SessionPool workerPool;
    private final String workerProgressReport;

    DelegatingSupervisorSession(SessionPool workerPool, String workerProgressReport) {
      this.workerPool = workerPool;
      this.workerProgressReport = workerProgressReport;
    }

    @Override
    public void addContext(Context context) {
      contexts.add(context);
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      contexts.add(context);
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.copyOf(contexts);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
      for (Session worker : workerPool.getAllSessions()) {
        Context delegation = new Context();
        delegation.setName(DelegationHandler.DELEGATION_CONTEXT_NAME);
        delegation.setType("text/plain");
        delegation.setContent("do the assigned work");
        worker.addContextIfChanged(delegation);

        if (workerProgressReport != null) {
          Context progress = new Context();
          progress.setName(WorkerProgressHandler.CONTEXT_NAME);
          progress.setType("text/plain");
          progress.setContent(workerProgressReport);
          worker.addContextIfChanged(progress);
        }
      }

      ResponseMessage response = new ResponseMessage("supervisor-id");
      response.setMessage("delegated");
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {}
  }
}
