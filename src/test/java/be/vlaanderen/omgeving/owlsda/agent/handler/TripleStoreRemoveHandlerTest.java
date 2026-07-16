package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class TripleStoreRemoveHandlerTest {

  private WorkerTripleStore tripleStore;
  private TripleStoreRemoveHandler handler;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-remove", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
    handler = new TripleStoreRemoveHandler(tripleStore, "worker-0");
  }

  @Test
  public void getName_ReturnsToolName() {
    assertEquals("triplestore_remove", handler.getName());
    assertEquals(TripleStoreRemoveHandler.NAME, handler.getName());
  }

  @Test
  public void getDescription_MentionsBlankNodeCleanup() {
    assertTrue(handler.getDescription().contains("blank node"));
  }

  @Test
  public void getArguments_RequiresSubject() {
    Map<String, Object> args = handler.getArguments();
    assertEquals(List.of("subject"), args.get("required"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) args.get("properties");
    assertTrue(properties.containsKey("subject"));
    assertTrue(properties.containsKey("predicate"));
    assertTrue(properties.containsKey("object"));
  }

  @Test
  public void handle_RemovesMatchingTriplesBySubject() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Person1 ex:name "Alice" ;
            ex:age 30 .
        ex:Person2 ex:name "Bob" .
        """,
        "worker-0");
    assertEquals(3L, tripleStore.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>)
            handler.handle(Map.of("subject", "http://example.org/Person1")).join();

    assertEquals("success", result.get("status"));
    assertEquals(2, result.get("triples_removed"));
    assertEquals(1L, result.get("total_triples"));
    assertEquals(1L, tripleStore.size());

    String remaining = tripleStore.getAllTriples();
    assertTrue(remaining.contains("Bob"));
  }

  @Test
  public void handle_RemovesOrphanedBlankNodes() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Person1 ex:hasAddress [
            ex:street "Main Street"
        ] .
        """,
        "worker-0");
    long initialSize = tripleStore.size();
    assertTrue(initialSize >= 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>)
            handler.handle(Map.of("subject", "http://example.org/Person1")).join();

    assertEquals((int) initialSize, result.get("triples_removed"));
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void handle_NoMatches_ReturnsZeroRemoved() {
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>)
            handler.handle(Map.of("subject", "http://example.org/DoesNotExist")).join();

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("triples_removed"));
    assertEquals(1L, tripleStore.size());
  }

  @Test
  public void handle_NoMatches_IncludesWarningToGuideRetry() {
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>)
            handler.handle(Map.of("subject", "http://example.org/DoesNotExist")).join();

    assertTrue(result.containsKey("warning"));
    assertTrue(((String) result.get("warning")).contains("triplestore_read"));
  }

  @Test
  public void handle_NoSubject_RejectsWithoutTouchingStore() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Person1 ex:name "Alice" .
        ex:Person2 ex:name "Bob" .
        """,
        "worker-0");
    assertEquals(2L, tripleStore.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of()).join();

    assertTrue("missing subject must be rejected as an error", result.containsKey("error"));
    assertEquals(
        "an unscoped call must not remove anything from the shared store", 2L, tripleStore.size());
  }

  @Test
  public void handle_PredicateOnly_RejectsWithoutErasingOtherWorkersData() {
    // A predicate shared across unrelated subjects (e.g. rdf:type/rdfs:label) must not be usable
    // to wipe every subject that has it - that would delete other workers' data too.
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Person1 ex:name "Alice" .
        ex:Person2 ex:name "Bob" .
        """,
        "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("predicate", "http://example.org/name")).join();

    assertTrue(result.containsKey("error"));
    assertEquals(2L, tripleStore.size());
  }
}
