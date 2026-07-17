package be.vlaanderen.omgeving.owlsda.webui;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the live benchmark output directory that the web UI visualizes. */
class RunDataReader {

  private static final Pattern MESSAGE_LOG_FILE = Pattern.compile("^(.*)-messages\\.json$");
  private static final Pattern WORKER_ROLE = Pattern.compile("^worker_(\\d+)$");

  private final Config config;

  RunDataReader(Config config) {
    this.config = config;
  }

  boolean benchmarkEnabled() {
    return config.getBenchmark() != null && config.getBenchmark().isEnabled();
  }

  /** How often, in seconds, a {@code LIVE} snapshot is captured while a run is in progress. */
  long liveIntervalSeconds() {
    return config.getBenchmark() != null ? config.getBenchmark().getLiveIntervalSeconds() : 15;
  }

  Path benchmarkDir() {
    String outputDir = config.getBenchmark() != null ? config.getBenchmark().getOutputDir() : null;
    return outputDir == null || outputDir.isBlank() ? null : Path.of(outputDir);
  }

  /** Reads {@code metadata.txt} as an ordered map, or an empty map if it doesn't exist yet. */
  Map<String, String> readMetadata() {
    Path dir = benchmarkDir();
    if (dir == null) {
      return Map.of();
    }
    Path metadataFile = dir.resolve("metadata.txt");
    if (!Files.exists(metadataFile)) {
      return Map.of();
    }

    Properties props = new Properties();
    try (BufferedReader reader = Files.newBufferedReader(metadataFile)) {
      props.load(reader);
    } catch (IOException e) {
      return Map.of();
    }

    Map<String, String> result = new LinkedHashMap<>();
    for (String name : props.stringPropertyNames()) {
      result.put(name, props.getProperty(name));
    }
    return result;
  }

  /** Raw contents of {@code benchmark-summary.json}, or {@code "[]"} if it doesn't exist yet. */
  String readHistoryJson() {
    Path dir = benchmarkDir();
    if (dir == null) {
      return "[]";
    }
    Path summaryFile = dir.resolve("benchmark-summary.json");
    return readFileOrDefault(summaryFile, "[]");
  }

  /** Session roles with a message log present, ordered supervisor, reviewer, then worker_0.. */
  List<String> listRoles() {
    Path dir = benchmarkDir();
    if (dir == null) {
      return List.of();
    }
    Path logsDir = dir.resolve("message_logs");
    if (!Files.isDirectory(logsDir)) {
      return List.of();
    }

    List<String> roles = new ArrayList<>();
    try (Stream<Path> files = Files.list(logsDir)) {
      files
          .map(p -> p.getFileName().toString())
          .forEach(
              fileName -> {
                Matcher matcher = MESSAGE_LOG_FILE.matcher(fileName);
                if (matcher.matches()) {
                  roles.add(matcher.group(1));
                }
              });
    } catch (IOException e) {
      return List.of();
    }

    roles.sort(Comparator.comparingInt(this::roleSortKey).thenComparing(Comparator.naturalOrder()));
    return roles;
  }

  private int roleSortKey(String role) {
    if ("supervisor".equals(role)) {
      return 0;
    }
    if ("reviewer".equals(role)) {
      return 1;
    }
    Matcher matcher = WORKER_ROLE.matcher(role);
    if (matcher.matches()) {
      return 2;
    }
    return 3;
  }

  /** Raw message log JSON for {@code role}, or {@code null} if the role is unknown. */
  String readMessageLog(String role) {
    if (role == null || !listRoles().contains(role)) {
      return null;
    }
    Path file = benchmarkDir().resolve("message_logs").resolve(role + "-messages.json");
    return readFileOrDefault(file, "[]");
  }

  /** Content of {@code triplestore.ttl} in the benchmark directory, if present. */
  TextFile readTriplestore() {
    Path dir = benchmarkDir();
    Path file = dir == null ? null : dir.resolve("triplestore.ttl");
    return readTextFile(file);
  }

  /** Content of the run's configured final output file, if present. */
  TextFile readFinalOutput() {
    String outputPath = config.getOutputPath();
    Path file = outputPath == null || outputPath.isBlank() ? null : Path.of(outputPath);
    return readTextFile(file);
  }

  private TextFile readTextFile(Path file) {
    if (file == null || !Files.exists(file)) {
      return new TextFile(file == null ? null : file.toString(), false, 0, "");
    }
    try {
      long size = Files.size(file);
      String content = Files.readString(file);
      return new TextFile(file.toString(), true, size, content);
    } catch (IOException e) {
      return new TextFile(file.toString(), false, 0, "");
    }
  }

  private String readFileOrDefault(Path file, String defaultValue) {
    if (!Files.exists(file)) {
      return defaultValue;
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return defaultValue;
    }
  }

  record TextFile(String path, boolean exists, long sizeBytes, String content) {}
}
