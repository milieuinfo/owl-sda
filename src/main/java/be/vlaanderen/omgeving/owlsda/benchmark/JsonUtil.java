package be.vlaanderen.omgeving.owlsda.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;

/**
 * JSON utility for serializing benchmark snapshots using Gson.
 */
public class JsonUtil {

  private static final Gson GSON = new GsonBuilder()
      .setPrettyPrinting()
      .create();

  /**
   * Converts a list of benchmark snapshots to a formatted JSON string.
   */
  public static String toJson(List<BenchmarkSnapshot> snapshots) {
    return GSON.toJson(snapshots);
  }

  /**
   * Converts any object to a formatted JSON string.
   */
  public static String toJsonObject(Object value) {
    return GSON.toJson(value);
  }
}
