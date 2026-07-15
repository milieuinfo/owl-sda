package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class TripleStoreClearHandlerTest {

  private WorkerTripleStore tripleStore;
  private TripleStoreClearHandler handler;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-clear", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
    handler = new TripleStoreClearHandler(tripleStore, "worker-0");
  }

  @Test
  public void getName_ReturnsToolName() {
    assertEquals("triplestore_clear", handler.getName());
    assertEquals(TripleStoreClearHandler.NAME, handler.getName());
  }

  @Test
  public void getDescription_WarnsAboutSharedStore() {
    assertTrue(handler.getDescription().contains("SHARED"));
  }

  @Test
  public void getArguments_RequiresConfirm() {
    Map<String, Object> args = handler.getArguments();
    assertEquals(List.of("confirm"), args.get("required"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) args.get("properties");
    assertTrue(properties.containsKey("confirm"));
  }

  @Test
  public void handle_WithConfirmTrue_ClearsAllTriples() {
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .", "worker-0");
    assertEquals(1L, tripleStore.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("confirm", true)).join();

    assertEquals("success", result.get("status"));
    assertEquals(1L, result.get("triples_removed"));
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void handle_WithoutConfirm_DoesNotClearAndReturnsError() {
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of()).join();

    assertTrue(result.containsKey("error"));
    assertEquals(1L, tripleStore.size());
  }

  @Test
  public void handle_WithConfirmFalse_DoesNotClear() {
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:a ex:b ex:c .", "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) handler.handle(Map.of("confirm", false)).join();

    assertTrue(result.containsKey("error"));
    assertEquals(1L, tripleStore.size());
  }
}
