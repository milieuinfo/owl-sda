package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class TripleStoreReadHandlerTest {

  private WorkerTripleStore tripleStore;
  private TripleStoreReadHandler handler;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-read", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
    handler = new TripleStoreReadHandler(tripleStore, "worker-0");
  }

  @Test
  public void getName_ReturnsToolName() {
    assertEquals("triplestore_read", handler.getName());
    assertEquals(TripleStoreReadHandler.NAME, handler.getName());
  }

  @Test
  public void getDescription_MentionsModes() {
    String description = handler.getDescription();
    assertTrue(description.contains("'all'"));
    assertTrue(description.contains("'query'"));
    assertTrue(description.contains("'count'"));
  }

  @Test
  public void getArguments_HasModeEnumAndNoRequiredFields() {
    Map<String, Object> args = handler.getArguments();
    assertEquals(List.of(), args.get("required"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) args.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> mode = (Map<String, Object>) properties.get("mode");
    assertEquals(List.of("all", "query", "count"), mode.get("enum"));
  }

  @Test
  public void handle_DefaultMode_ReturnsAllTriplesAsTurtle() {
    tripleStore.addTriples(
        "@prefix ex: <http://example.org/> .\nex:Person1 ex:name \"Alice\" .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of()).join();

    assertEquals("success", result.get("status"));
    assertEquals(1L, result.get("count"));
    String data = (String) result.get("data");
    assertTrue(data.contains("Alice"));
  }

  @Test
  public void handle_AllModeWithEmptyStore_ReturnsEmptyData() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of("mode", "all")).join();

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("count"));
    assertEquals("", result.get("data"));
  }

  @Test
  public void handle_CountMode_ReturnsTripleCount() {
    tripleStore.addTriples(
        "@prefix ex: <http://example.org/> .\nex:a ex:b ex:c, ex:d .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("mode", "count")).join();

    assertEquals("success", result.get("status"));
    assertEquals(2L, result.get("count"));
  }

  @Test
  public void handle_QueryModeBySubject_ReturnsMatchingTriples() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Person1 ex:name "Alice" .
        ex:Person2 ex:name "Bob" .
        """,
        "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>)
            handler.handle(Map.of("mode", "query", "subject", "http://example.org/Person1")).join();

    assertEquals("success", result.get("status"));
    assertEquals(1, result.get("count"));
    @SuppressWarnings("unchecked")
    List<String> triples = (List<String>) result.get("triples");
    assertTrue(triples.getFirst().contains("Alice"));
  }

  @Test
  public void handle_UnknownMode_ReturnsError() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("mode", "bogus")).join();

    assertTrue(((String) result.get("error")).contains("Unknown mode"));
  }
}
