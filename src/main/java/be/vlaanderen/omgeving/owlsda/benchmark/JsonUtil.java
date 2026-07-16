package be.vlaanderen.omgeving.owlsda.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

/** JSON utility for serializing benchmark snapshots using Gson. */
public class JsonUtil {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Type SNAPSHOT_LIST_TYPE =
      new TypeToken<List<BenchmarkSnapshot>>() {}.getType();

  /** Converts a list of benchmark snapshots to a formatted JSON string. */
  public static String toJson(List<BenchmarkSnapshot> snapshots) {
    return GSON.toJson(snapshots);
  }

  /** Converts any object to a formatted JSON string. */
  public static String toJsonObject(Object value) {
    return GSON.toJson(value);
  }

  /**
   * Parses a JSON array of benchmark snapshots back into a list. Returns an empty list on blank
   * input.
   */
  public static List<BenchmarkSnapshot> fromJsonSnapshotList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<BenchmarkSnapshot> result = GSON.fromJson(json, SNAPSHOT_LIST_TYPE);
    return result != null ? result : List.of();
  }
}
