package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MemorySetHandlerTest {

  @Test
  public void handle_ValidKeyAndValue_StoresAndReturnsSuccess() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    MemorySetHandler handler = new MemorySetHandler(store, "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "key", "finding-1",
        "value", "ex:Asset42 needs ex:identifier"
    )).join();

    assertEquals("success", result.get("status"));
    assertEquals("finding-1", result.get("key"));
    assertEquals(1, result.get("total_entries"));
    assertEquals(java.util.Optional.of("ex:Asset42 needs ex:identifier"), store.get("finding-1"));
  }

  @Test
  public void handle_StoreRejectsValue_ReturnsErrorMap() {
    RunMemoryStore store = new RunMemoryStore(10, 5);
    MemorySetHandler handler = new MemorySetHandler(store, "worker-0");

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "key", "finding-1",
        "value", "value too long"
    )).join();

    assertTrue(((String) result.get("error")).contains("max-value-bytes"));
  }
}
