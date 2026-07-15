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
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Tests for {@link SupervisorWorkflow}, in particular the round-loop stall detection documented on
 * {@link SupervisorWorkflow#run(int)}'s Javadoc: three independent, consecutive-round counters
 * (empty delegation, no progress, stalled violations) that each abort the run once their threshold
 * is reached, and reset the moment their condition improves.
 *
 * <p>{@code Supervisor} and {@code SupervisorWorkflow} are records (implicitly final) so Mockito
 * cannot mock them directly; instead these tests build a <em>real</em> {@code Supervisor} and a
 * real {@code SupervisorWorkflow} wired to a mocked {@code ConcurrentWorkerBatch} (a concrete,
 * non-final class) plus a hand-written {@link Session} double that simulates the supervisor LLM's
 * delegation behavior. This lets each round's success/failure be scripted precisely while the real
 * round-loop logic in {@code runGenerationPhase} runs unmodified.
 */
public class SupervisorWorkflowTest {

  static {
    // See SupervisorTest for why this is required on Java 25 with Mockito 5.14.x's bundled
    // ByteBuddy version. Must be set before the first Mockito.mock() call in this JVM.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final String NS = "http://example.org/";

  // ---------------------------------------------------------------------
  // calculateNextShapesTarget (private) - isolated logic
  // ---------------------------------------------------------------------

  @Test
  public void calculateNextShapesTarget_NoProcessedShapes_ReturnsUnchanged() throws Exception {
    Shacl shacl = shaclWithClasses("Person");
    WorkerTripleStore store = storeWithIrrelevantTriple();
    SupervisorWorkflow workflow =
        new SupervisorWorkflow(new Config(), shacl, null, null, null, store, null);

    int result = invokeCalculateNextShapesTarget(workflow, 1, 5, 2, 3);

    assertEquals("nothing processed yet, so the scope must not expand", 1, result);
  }

  @Test
  public void calculateNextShapesTarget_ProcessedShapeWithNoViolations_ExpandsByPoolTimesBatch()
      throws Exception {
    Shacl shacl = shaclWithClasses("Person");
    shacl.getShapes().get(0).setProcessed(true);
    WorkerTripleStore store = new WorkerTripleStore(null);
    // Conforming instance of the only (unconstrained) shape's target class.
    store.addTriples("<" + NS + "p1> <" + RDF.type.getURI() + "> <" + NS + "Person> .", "TEST");

    SupervisorWorkflow workflow =
        new SupervisorWorkflow(new Config(), shacl, null, null, null, store, null);

    int result = invokeCalculateNextShapesTarget(workflow, 1, 5, 2, 3);

    assertEquals("expand by poolCount*batchSize, capped at totalShapes", 5, result);
  }

  @Test
  public void calculateNextShapesTarget_AlreadyAtTotal_ReturnsUnchangedEvenWithProgress()
      throws Exception {
    Shacl shacl = shaclWithClasses("Person");
    shacl.getShapes().get(0).setProcessed(true);
    WorkerTripleStore store = new WorkerTripleStore(null);
    store.addTriples("<" + NS + "p1> <" + RDF.type.getURI() + "> <" + NS + "Person> .", "TEST");

    SupervisorWorkflow workflow =
        new SupervisorWorkflow(new Config(), shacl, null, null, null, store, null);

    int result = invokeCalculateNextShapesTarget(workflow, 5, 5, 2, 3);

    assertEquals(
        "currentShapes already equals totalShapes; nothing left to expand into", 5, result);
  }

  @Test
  public void calculateNextShapesTarget_ExpansionIsCappedAtTotalShapes() throws Exception {
    Shacl shacl = shaclWithClasses("Person");
    shacl.getShapes().get(0).setProcessed(true);
    WorkerTripleStore store = new WorkerTripleStore(null);
    store.addTriples("<" + NS + "p1> <" + RDF.type.getURI() + "> <" + NS + "Person> .", "TEST");

    SupervisorWorkflow workflow =
        new SupervisorWorkflow(new Config(), shacl, null, null, null, store, null);

    // poolCount*batchSize = 100, far beyond totalShapes - must cap, not overshoot.
    int result = invokeCalculateNextShapesTarget(workflow, 1, 4, 10, 10);

    assertEquals(4, result);
  }

  // ---------------------------------------------------------------------
  // shouldAbortAfterFailedRound (private) - isolated logic
  // ---------------------------------------------------------------------

  @Test
  public void shouldAbortAfterFailedRound_BelowThreshold_DoesNotAbort() throws Exception {
    SupervisorWorkflow workflow = emptyWorkflow();

    assertFalse(invokeShouldAbort(workflow, 5, 0, 0));
    assertFalse(invokeShouldAbort(workflow, 5, 0, 1));
  }

  @Test
  public void shouldAbortAfterFailedRound_ReachesThreshold_Aborts() throws Exception {
    SupervisorWorkflow workflow = emptyWorkflow();

    // MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS = 3: the 3rd consecutive empty-delegation round
    // (consecutiveEmptyDelegationRounds already at 2 going in) must abort.
    assertTrue(invokeShouldAbort(workflow, 5, 0, 2));
  }

  @Test
  public void shouldAbortAfterFailedRound_PastThreshold_StillAborts() throws Exception {
    SupervisorWorkflow workflow = emptyWorkflow();

    assertTrue(invokeShouldAbort(workflow, 5, 0, 5));
  }

  @Test
  public void shouldAbortAfterFailedRound_WorkersWereDelegated_NeverAbortsRegardlessOfCounter()
      throws Exception {
    SupervisorWorkflow workflow = emptyWorkflow();

    // delegatedWorkers > 0 means the batch itself failed (e.g. timeout), not an empty-delegation
    // round; that always just retries and never aborts through this path.
    assertFalse(invokeShouldAbort(workflow, 5, 1, 10));
    assertFalse(invokeShouldAbort(workflow, 5, 3, 100));
  }

  // ---------------------------------------------------------------------
  // runGenerationPhase (via the public run() entry point) - end-to-end stall detection
  // ---------------------------------------------------------------------

  @Test
  public void run_SupervisorNeverDelegates_AbortsAfterThreeConsecutiveEmptyRounds()
      throws Exception {
    Shacl shacl = shaclWithClasses("Widget", "Gadget", "Thing");
    SessionPool workerPool = poolOf(new FakeWorkerSession());

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);

    AtomicInteger promptCount = new AtomicInteger();
    Session silentSupervisorSession = new SilentSupervisorSession(promptCount);

    OutputValidator validator = mock(OutputValidator.class);
    when(validator.validate()).thenReturn(null);

    Supervisor supervisor =
        new Supervisor(silentSupervisorSession, validator, batch, shacl, null, null);

    Config config = new Config();
    BenchmarkService benchmarkService = new BenchmarkService(config); // disabled by default
    SupervisorWorkflow workflow =
        new SupervisorWorkflow(config, shacl, supervisor, null, benchmarkService, null, null);

    workflow.run(1);

    // Each failed round = 1 initial prompt + 1 strict-retry prompt (see Supervisor.delegate()).
    // MAX_CONSECUTIVE_EMPTY_DELEGATION_ROUNDS = 3, so the run must stop after exactly 3 rounds:
    // neither 2 rounds' worth (4 prompts, aborting too early) nor 4 rounds' worth (8 prompts,
    // aborting too late).
    assertEquals(6, promptCount.get());
    assertEquals(0, countProcessedShapes(shacl));
  }

  @Test
  public void run_DelegationSucceedsButNoShapesEverComplete_AbortsAfterNoProgressThreshold()
      throws Exception {
    // Single, always-unconstrained shape whose target class never appears in the shared store, so
    // ShapeCompletionEvaluator can never auto-complete it; combined with a supervisor session that
    // never files worker-progress reports, processedShapes is pinned at 0 for the whole run.
    Shacl shacl = shaclWithClasses("Person");
    SessionPool workerPool = poolOf(new FakeWorkerSession());

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);
    when(batch.runWithDelegation(anyString(), anyInt())).thenReturn(true);

    // Store has data, but none of it is an instance of ex:Person, so the shape can never
    // auto-complete and validation stays clean (currentViolations == 0) every round.
    WorkerTripleStore store = storeWithIrrelevantTriple();

    AtomicInteger promptCount = new AtomicInteger();
    Session alwaysDelegatingSession =
        new AlwaysDelegatingSupervisorSession(workerPool, promptCount);

    OutputValidator validator = mock(OutputValidator.class);
    when(validator.validate()).thenReturn(null);

    Supervisor supervisor =
        new Supervisor(alwaysDelegatingSession, validator, batch, shacl, store, null);

    Config config = new Config();
    BenchmarkService benchmarkService = new BenchmarkService(config);
    SupervisorWorkflow workflow =
        new SupervisorWorkflow(config, shacl, supervisor, null, benchmarkService, store, null);

    workflow.run(1);

    // MAX_CONSECUTIVE_NO_PROGRESS_ROUNDS = 5. Round 1 always counts as "progress" because the
    // very first round is measured against the MAX_VALUE violations sentinel, so 5 *more*
    // consecutive no-progress rounds are needed (rounds 2-6) before the counter reaches the
    // threshold, at which point the workflow makes one final aggressive-delegation attempt before
    // giving up. Total orchestrate()/prompt() calls = 6 regular rounds + 1 final attempt = 7.
    assertEquals(7, promptCount.get());
    assertEquals(0, countProcessedShapes(shacl));
  }

  @Test
  public void run_ConstantUnresolvedViolation_AbortsAfterStalledViolationThreshold()
      throws Exception {
    // A Person instance that permanently violates the required-hasName shape. Delegation always
    // "succeeds" (the fake session marks a worker as delegated every round) but nothing ever
    // touches the shared store, so the exact same violation (same focus node) recurs every round.
    Shacl shacl = shaclWithHasNameRestriction();
    SessionPool workerPool = poolOf(new FakeWorkerSession());

    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.workerSessionPool()).thenReturn(workerPool);
    when(batch.config()).thenReturn(null);
    when(batch.runWithDelegation(anyString(), anyInt())).thenReturn(true);

    WorkerTripleStore store = new WorkerTripleStore(null);
    // Missing the required ex:hasName -> permanently violates minCount 1 for the same focus node.
    store.addTriples("<" + NS + "p1> <" + RDF.type.getURI() + "> <" + NS + "Person> .", "TEST");

    AtomicInteger promptCount = new AtomicInteger();
    Session alwaysDelegatingSession =
        new AlwaysDelegatingSupervisorSession(workerPool, promptCount);

    OutputValidator validator = mock(OutputValidator.class);
    when(validator.validate()).thenReturn(null);

    Supervisor supervisor =
        new Supervisor(alwaysDelegatingSession, validator, batch, shacl, store, null);

    Config config = new Config();
    BenchmarkService benchmarkService = new BenchmarkService(config);
    SupervisorWorkflow workflow =
        new SupervisorWorkflow(config, shacl, supervisor, null, benchmarkService, store, null);

    workflow.run(1);

    // MAX_CONSECUTIVE_STALLED_VIOLATION_ROUNDS = 4. Round 1 establishes the baseline signature
    // (measured against the MAX_VALUE sentinel, so it never counts as "stalled" itself). Rounds
    // 2-5 then see the identical violation signature four times in a row, hitting the threshold
    // exactly at round 5 - and unlike the no-progress guard, this abort path has no extra "final
    // attempt" call, so total orchestrate()/prompt() calls = exactly 5.
    assertEquals(5, promptCount.get());
    assertFalse(shacl.getShapes().get(0).isProcessed());
  }

  // ---------------------------------------------------------------------
  // Fixtures / helpers
  // ---------------------------------------------------------------------

  private static SupervisorWorkflow emptyWorkflow() {
    return new SupervisorWorkflow(null, null, null, null, null, null, null);
  }

  private static int invokeCalculateNextShapesTarget(
      SupervisorWorkflow workflow, int currentShapes, int totalShapes, int poolCount, int batchSize)
      throws Exception {
    Method method =
        SupervisorWorkflow.class.getDeclaredMethod(
            "calculateNextShapesTarget", int.class, int.class, int.class, int.class);
    method.setAccessible(true);
    return (int) method.invoke(workflow, currentShapes, totalShapes, poolCount, batchSize);
  }

  private static boolean invokeShouldAbort(
      SupervisorWorkflow workflow, int shapes, int delegatedWorkers, int consecutiveEmptyRounds)
      throws Exception {
    Method method =
        SupervisorWorkflow.class.getDeclaredMethod(
            "shouldAbortAfterFailedRound", int.class, int.class, int.class);
    method.setAccessible(true);
    return (boolean) method.invoke(workflow, shapes, delegatedWorkers, consecutiveEmptyRounds);
  }

  private static int countProcessedShapes(Shacl shacl) {
    return (int) shacl.getShapes().stream().filter(Shacl.Shape::isProcessed).count();
  }

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

  /** A store with one triple whose subject/type never matches any test shape's target class. */
  private static WorkerTripleStore storeWithIrrelevantTriple() {
    WorkerTripleStore store = new WorkerTripleStore(null);
    store.addTriples("<" + NS + "x> <" + NS + "p> <" + NS + "y> .", "SETUP");
    return store;
  }

  private static SessionPool poolOf(Session... sessions) {
    SessionPool pool = new SessionPool(sessions.length);
    for (Session session : sessions) {
      pool.addSession(session);
    }
    return pool;
  }

  /** Minimal worker-pool session double supporting context add/read, nothing else. */
  private static final class FakeWorkerSession implements Session {
    private final List<Context> contexts = new ArrayList<>();

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
  private static class SilentSupervisorSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private final AtomicInteger promptCount;

    SilentSupervisorSession(AtomicInteger promptCount) {
      this.promptCount = promptCount;
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
      promptCount.incrementAndGet();
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

  /**
   * Supervisor session double that simulates the supervisor always successfully delegating: every
   * prompt() marks every worker session in {@code workerPool} as having received delegation
   * instructions (mirroring what {@code SessionManager#publishDelegationContext} would do in
   * production), but deliberately never files a structured worker-progress report, so no shape can
   * ever be marked completed via that path.
   */
  private static final class AlwaysDelegatingSupervisorSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private final SessionPool workerPool;
    private final AtomicInteger promptCount;

    AlwaysDelegatingSupervisorSession(SessionPool workerPool, AtomicInteger promptCount) {
      this.workerPool = workerPool;
      this.promptCount = promptCount;
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
      promptCount.incrementAndGet();
      for (Session worker : workerPool.getAllSessions()) {
        Context delegation = new Context();
        delegation.setName(DelegationHandler.DELEGATION_CONTEXT_NAME);
        delegation.setType("text/plain");
        delegation.setContent("do the assigned work");
        worker.addContextIfChanged(delegation);
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
