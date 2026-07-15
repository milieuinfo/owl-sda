package be.vlaanderen.omgeving.owlsda.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonUtilTest {

  @Test
  public void toJson_EmptyList_ProducesEmptyJsonArray() {
    String json = JsonUtil.toJson(List.of());

    JsonElement parsed = JsonParser.parseString(json);
    assertTrue(parsed.isJsonArray());
    assertEquals(0, parsed.getAsJsonArray().size());
  }

  @Test
  public void toJson_ListOfSnapshots_SerializesFieldsAsJsonArray() {
    BenchmarkSnapshot snapshot =
        BenchmarkSnapshot.builder()
            .timestamp("20260713_120000_000")
            .stage("GENERATE")
            .shapesProcessed(3)
            .durationMs(1500L)
            .triplestoreSize(42L)
            .triplestoreEmpty(false)
            .currentViolations(1)
            .snapshotDirectory("20260713_120000_000")
            .build();

    String json = JsonUtil.toJson(List.of(snapshot));

    JsonArray array = JsonParser.parseString(json).getAsJsonArray();
    assertEquals(1, array.size());
    JsonObject obj = array.get(0).getAsJsonObject();
    assertEquals("20260713_120000_000", obj.get("timestamp").getAsString());
    assertEquals("GENERATE", obj.get("stage").getAsString());
    assertEquals(3, obj.get("shapesProcessed").getAsInt());
    assertEquals(1500L, obj.get("durationMs").getAsLong());
    assertEquals(42L, obj.get("triplestoreSize").getAsLong());
    assertEquals(false, obj.get("triplestoreEmpty").getAsBoolean());
    assertEquals(1, obj.get("currentViolations").getAsInt());
  }

  @Test
  public void toJson_IsPrettyPrinted() {
    BenchmarkSnapshot snapshot =
        BenchmarkSnapshot.builder().timestamp("t").stage("GENERATE").build();

    String json = JsonUtil.toJson(List.of(snapshot));

    assertTrue("Expected pretty-printed JSON with newlines", json.contains("\n"));
  }

  @Test
  public void toJsonObject_ArbitraryObject_RoundTripsThroughGson() {
    Map<String, Object> value = Map.of("key", "value", "count", 3);

    String json = JsonUtil.toJsonObject(value);

    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
    assertEquals("value", obj.get("key").getAsString());
    assertEquals(3, obj.get("count").getAsInt());
  }

  @Test
  public void toJsonObject_Null_ReturnsJsonNullLiteral() {
    String json = JsonUtil.toJsonObject(null);

    assertEquals("null", json);
  }

  @Test
  public void toJsonObject_ListOfRecords_SerializesEachElement() {
    List<be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry> entries =
        List.of(
            new be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry(
                "2026-07-13T12:00:00Z", "outbound", "msg-1", "hello"));

    String json = JsonUtil.toJsonObject(entries);

    JsonArray array = JsonParser.parseString(json).getAsJsonArray();
    assertEquals(1, array.size());
    JsonObject obj = array.get(0).getAsJsonObject();
    assertEquals("msg-1", obj.get("messageId").getAsString());
    assertEquals("hello", obj.get("content").getAsString());
  }
}
