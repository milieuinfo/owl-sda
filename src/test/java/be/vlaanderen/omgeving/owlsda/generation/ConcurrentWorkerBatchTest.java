package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConcurrentWorkerBatchTest {

  @Test
  public void run_AllWorkersSucceed_ReturnsTrue() throws InterruptedException {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    SessionPool pool = poolOf(succeedingSession(), succeedingSession());
    ConcurrentWorkerBatch batch = new ConcurrentWorkerBatch(null, pool, shacl, null);

    boolean result = batch.run("generate instances", 2, false);

    assertTrue(result);
    batch.shutdown();
  }

  @Test
  public void run_OneWorkerFails_ReturnsFalseOverall() throws InterruptedException {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    SessionPool pool = poolOf(succeedingSession(), failingSession());
    ConcurrentWorkerBatch batch = new ConcurrentWorkerBatch(null, pool, shacl, null);

    boolean result = batch.run("generate instances", 2, false);

    assertFalse(result);
    batch.shutdown();
  }

  @Test
  public void run_MultipleRoundsOnSameBatch_ReuseLongLivedExecutor() throws InterruptedException {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    SessionPool pool = poolOf(succeedingSession());
    ConcurrentWorkerBatch batch = new ConcurrentWorkerBatch(null, pool, shacl, null);

    assertTrue(batch.run("round 1", 2, false));
    assertTrue(batch.run("round 2", 2, false));

    batch.shutdown();
  }

  @Test
  public void getWorkerCount_ReturnsPoolSize() {
    SessionPool pool = poolOf(succeedingSession(), succeedingSession(), succeedingSession());
    ConcurrentWorkerBatch batch = new ConcurrentWorkerBatch(null, pool, shaclWithClasses("Sensor"), null);

    assertEquals(3, batch.getWorkerCount());

    batch.shutdown();
  }

  @Test(expected = RejectedExecutionException.class)
  public void run_AfterShutdown_RejectsNewWork() throws InterruptedException {
    Shacl shacl = shaclWithClasses("Sensor");
    SessionPool pool = poolOf(succeedingSession());
    ConcurrentWorkerBatch batch = new ConcurrentWorkerBatch(null, pool, shacl, null);

    batch.shutdown();

    batch.run("after shutdown", 1, false);
  }

  private Shacl shaclWithClasses(String... localNames) {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    for (String localName : localNames) {
      Resource cls = ontology.createResource(ns + localName);
      cls.addProperty(RDF.type, OWL.Class);
    }

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();
    return shacl;
  }

  private SessionPool poolOf(Session... sessions) {
    SessionPool pool = new SessionPool(sessions.length);
    for (Session session : sessions) {
      pool.addSession(session);
    }
    return pool;
  }

  private Session succeedingSession() {
    return new FakeSession(false);
  }

  private Session failingSession() {
    return new FakeSession(true);
  }

  private static final class FakeSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private final boolean shouldFail;

    private FakeSession(boolean shouldFail) {
      this.shouldFail = shouldFail;
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
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      if (shouldFail) {
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("simulated worker failure"));
        return future;
      }
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
    }
  }
}
