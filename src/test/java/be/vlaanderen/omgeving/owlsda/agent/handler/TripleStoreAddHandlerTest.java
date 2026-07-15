package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class TripleStoreAddHandlerTest {

  private WorkerTripleStore tripleStore;
  private TripleStoreAddHandler handler;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-add", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
    handler = new TripleStoreAddHandler(tripleStore, "worker-0");
  }

  @Test
  public void getName_ReturnsToolName() {
    assertEquals("triplestore_add", handler.getName());
    assertEquals(TripleStoreAddHandler.NAME, handler.getName());
  }

  @Test
  public void getDescription_IsNonEmpty() {
    assertTrue(handler.getDescription().contains("SHARED"));
  }

  @Test
  public void getArguments_DeclaresRequiredDataField() {
    Map<String, Object> args = handler.getArguments();
    assertEquals("object", args.get("type"));
    assertEquals(List.of("data"), args.get("required"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) args.get("properties");
    assertTrue(properties.containsKey("data"));
  }

  @Test
  public void handle_ValidTurtle_AddsTriplesAndReportsSuccess() {
    String turtle =
        """
        @prefix ex: <http://example.org/> .

        ex:Person1 a ex:Person ;
            ex:name "Alice" .
        """;

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("data", turtle)).join();

    assertEquals("success", result.get("status"));
    assertEquals(2, result.get("triples_added"));
    assertEquals(2, result.get("total_triples"));
    assertEquals(2L, tripleStore.size());

    List<String> queried =
        tripleStore.queryTriples("http://example.org/Person1", null, null, "worker-0");
    assertEquals(2, queried.size());
  }

  @Test
  public void handle_DuplicateTriples_ReportsDuplicatesAndWarning() {
    String turtle =
        """
        @prefix ex: <http://example.org/> .

        ex:Person1 ex:name "Alice" .
        """;
    tripleStore.addTriples(turtle, "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("data", turtle)).join();

    assertEquals("success", result.get("status"));
    assertEquals(0, result.get("triples_added"));
    assertEquals(1, result.get("duplicates_ignored"));
    assertTrue(result.containsKey("warning"));
  }

  @Test
  public void handle_MissingData_ReturnsError() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of()).join();

    assertEquals("No data provided", result.get("error"));
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void handle_BlankData_ReturnsError() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of("data", "   ")).join();

    assertEquals("No data provided", result.get("error"));
  }

  @Test
  public void handle_InvalidTurtle_ReturnsErrorWithoutThrowing() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("data", "not turtle at all {{{")).join();

    assertNotNull(result.get("error"));
    assertFalse(result.containsKey("status"));
  }
}
