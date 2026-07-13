package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MemoryGetHandlerTest {

  @Test
  public void handle_KnownKey_ReturnsFoundAndValue() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    store.put("finding-1", "ex:Asset42 needs ex:identifier", "worker-0");
    MemoryGetHandler handler = new MemoryGetHandler(store);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "key", "finding-1"
    )).join();

    assertEquals(true, result.get("found"));
    assertEquals("finding-1", result.get("key"));
    assertEquals("ex:Asset42 needs ex:identifier", result.get("value"));
  }

  @Test
  public void handle_UnknownKey_ReturnsNotFound() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    MemoryGetHandler handler = new MemoryGetHandler(store);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "key", "missing"
    )).join();

    assertEquals(false, result.get("found"));
  }

  @Test
  public void handle_OmittedKey_ListsAllKeys() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    store.put("finding-1", "v1", "worker-0");
    store.put("finding-2", "v2", "worker-1");
    MemoryGetHandler handler = new MemoryGetHandler(store);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of()).join();

    assertEquals(List.of("finding-1", "finding-2"), result.get("keys"));
  }
}
