package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.handler.RunMemoryStore.PutResult;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunMemoryStoreTest {

  @Test
  public void put_ThenGet_RoundTrips() {
    RunMemoryStore store = new RunMemoryStore(10, 100);

    PutResult result = store.put("k1", "v1", "worker-0");

    assertTrue(result.isSuccess());
    assertEquals(1, result.getTotalEntries());
    assertEquals(Optional.of("v1"), store.get("k1"));
  }

  @Test
  public void get_UnknownKey_ReturnsEmpty() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    assertEquals(Optional.empty(), store.get("missing"));
  }

  @Test
  public void put_BlankKey_ReturnsError() {
    RunMemoryStore store = new RunMemoryStore(10, 100);

    PutResult result = store.put("  ", "value", "worker-0");

    assertFalse(result.isSuccess());
    assertEquals("key is required", result.getError());
  }

  @Test
  public void put_NullValue_ReturnsError() {
    RunMemoryStore store = new RunMemoryStore(10, 100);

    PutResult result = store.put("k1", null, "worker-0");

    assertFalse(result.isSuccess());
    assertEquals("value is required", result.getError());
  }

  @Test
  public void put_ValueExceedingMaxBytes_ReturnsError() {
    RunMemoryStore store = new RunMemoryStore(10, 5);

    PutResult result = store.put("k1", "too long a value", "worker-0");

    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("max-value-bytes"));
  }

  @Test
  public void put_BeyondMaxEntries_ReturnsError() {
    RunMemoryStore store = new RunMemoryStore(1, 100);
    store.put("k1", "v1", "worker-0");

    PutResult result = store.put("k2", "v2", "worker-0");

    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("memory store is full"));
  }

  @Test
  public void put_OverwritingExistingKey_DoesNotCountAgainstMaxEntries() {
    RunMemoryStore store = new RunMemoryStore(1, 100);
    store.put("k1", "v1", "worker-0");

    PutResult result = store.put("k1", "v2", "worker-0");

    assertTrue(result.isSuccess());
    assertEquals(Optional.of("v2"), store.get("k1"));
  }

  @Test
  public void listKeys_ReturnsAllStoredKeys() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    store.put("k1", "v1", "worker-0");
    store.put("k2", "v2", "worker-1");

    assertEquals(List.of("k1", "k2"), store.listKeys());
  }

  @Test
  public void clear_RemovesAllEntries() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    store.put("k1", "v1", "worker-0");

    store.clear();

    assertTrue(store.listKeys().isEmpty());
    assertEquals(Optional.empty(), store.get("k1"));
  }

  @Test
  public void remove_ExistingKey_ReturnsTrueAndRemoves() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    store.put("k1", "v1", "worker-0");

    assertTrue(store.remove("k1"));
    assertEquals(Optional.empty(), store.get("k1"));
  }

  @Test
  public void remove_MissingKey_ReturnsFalse() {
    RunMemoryStore store = new RunMemoryStore(10, 100);
    assertFalse(store.remove("missing"));
  }
}
