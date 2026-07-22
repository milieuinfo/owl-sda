package be.vlaanderen.omgeving.owlsda.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@YamlConfig
public class Config {
  private String inputPath;
  private String outputPath;
  private String userInput;
  private List<UserContextEntry> userContext = new ArrayList<>();

  // Hard timeout for the whole program run. 0 disables it.
  private long programTimeoutMs = 0L;

  private ClientProperties client = new ClientProperties();
  private ExtractExternalProperties extract = new ExtractExternalProperties();
  private ReasonerProperties reasoner = new ReasonerProperties();
  private ShaclProperties shacl = new ShaclProperties();
  private ToolsProperties tools = new ToolsProperties();
  private CompactionProperties compaction = new CompactionProperties();
  private String logLevel = "INFO";

  private boolean logToFile = false;
  private String logFilePath = "logs/owlsda.log";

  private BenchmarkProperties benchmark = new BenchmarkProperties();
  private GenerationProperties generation = new GenerationProperties();
  private OntologyProperties ontology = new OntologyProperties();

  public DataRichness getDataRichness() {
    return DataRichness.from(generation != null ? generation.getDataRichness() : null);
  }

  /** Convenience method to access batch size from worker properties. */
  public int getBatchSize() {
    return client.getWorker().getBatchSize();
  }

  /** Convenience method to access pool count from worker properties. */
  public int getPoolCount() {
    return client.getWorker().getPoolCount();
  }

  /** Load from a filesystem path. */
  public static Config loadFile(String path) throws IOException {
    return YamlConfigLoader.loadFromPath(path, Config.class);
  }

  /** Load from a classpath resource. */
  public static Config loadClasspath(String resource) throws IOException {
    return YamlConfigLoader.loadFromClasspath(resource, Config.class);
  }

  /**
   * Smart loader: accepts either a prefix ("classpath:" or "file:") or a plain location. If a plain
   * location is provided, this method first attempts to load it as a classpath resource and falls
   * back to a filesystem path.
   */
  public static Config load(String location) throws IOException {
    if (location == null) {
      throw new IllegalArgumentException("location must not be null");
    }

    if (location.startsWith("classpath:")) {
      String res = location.substring("classpath:".length());
      return loadClasspath(res);
    }

    if (location.startsWith("file:")) {
      String p = location.substring("file:".length());
      return loadFile(p);
    }

    // try classpath first
    InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(location);
    if (in != null) {
      // resource exists on classpath
      return loadClasspath(location);
    }

    // fall back to filesystem path
    return loadFile(location);
  }

  @Setter
  @Getter
  @YamlConfig
  public static class UserContextEntry {
    private String name;
    private String path;
    private String url;

    public String getSource() {
      if (url != null && !url.isBlank()) {
        return url;
      }
      return path;
    }

    public boolean hasSource() {
      return getSource() != null && !getSource().isBlank();
    }
  }

  @Setter
  @Getter
  @YamlConfig
  public static class ClientProperties {
    private String provider = "copilot";
    private OllamaProperties ollama = new OllamaProperties();
    private OpenAiCompatibleProperties openaiCompatible = new OpenAiCompatibleProperties();
    private WorkerProperties worker = new WorkerProperties();
    private SupervisorProperties supervisor = new SupervisorProperties();
    private ReviewerProperties reviewer = new ReviewerProperties();

    @Setter
    @Getter
    @YamlConfig
    public static class OllamaProperties {
      private String baseUrl = "http://localhost:11434";
      // Thinking-capable models (e.g. qwen3) default to emitting chain-of-thought reasoning
      // before each answer, which is slow. Set to false to request plain answers via Ollama's
      // "think" request field.
      private boolean think = true;
    }

    @Setter
    @Getter
    @YamlConfig
    public static class OpenAiCompatibleProperties {
      private String baseUrl = "https://api.openai.com/v1";
      // Nullable; blank/unset falls back to the OPENAI_API_KEY environment variable.
      private String apiKey;

      public String resolveApiKey() {
        return resolveApiKey(System.getenv("OPENAI_API_KEY"));
      }

      // Package-visible so tests can exercise the fallback logic without touching real env vars.
      String resolveApiKey(String envFallback) {
        if (apiKey != null && !apiKey.isBlank()) {
          return apiKey.trim();
        }
        return (envFallback != null && !envFallback.isBlank()) ? envFallback.trim() : null;
      }
    }

    @Setter
    @Getter
    @YamlConfig
    public static class WorkerProperties {
      // Optional per-role provider override (e.g. ollama, copilot, openai-compatible).
      // Falls back to client.provider when omitted.
      private String provider;
      private String model = "gpt-5.4";
      private int timeoutMs = 60000;
      // Max idle time between assistant events (messages/tool requests). 0 disables this guard.
      private int betweenMessageTimeoutMs = 0;
      private int batchSize = 5;
      private int poolCount = 1;
      // This model's real context window in tokens, as configured on the inference server (e.g.
      // llama.cpp/llama-swap --ctx-size). 0 means unset: compaction falls back to
      // CompactionProperties' fixed token-count threshold instead of a window-relative ratio.
      private int contextWindowTokens = 0;
    }

    @Setter
    @Getter
    @YamlConfig
    public static class SupervisorProperties {
      // Optional per-role provider override (e.g. ollama, copilot, openai-compatible).
      // Falls back to client.provider when omitted.
      private String provider;
      private String model = "gpt-5.4";
      private int timeoutMs = 120000; // Supervisor may need more time for orchestration
      // Max idle time between assistant events (messages/tool requests). 0 disables this guard.
      private int betweenMessageTimeoutMs = 0;
      // This model's real context window in tokens; see WorkerProperties#contextWindowTokens.
      private int contextWindowTokens = 0;
    }

    @Setter
    @Getter
    @YamlConfig
    public static class ReviewerProperties {
      // Optional per-role provider override (e.g. ollama, copilot, openai-compatible).
      // Falls back to client.provider when omitted.
      private String provider;
      private String model = "claude-4.6-sonnet";
      private int timeoutMs = 60000;
      // Max idle time between assistant events (messages/tool requests). 0 disables this guard.
      private int betweenMessageTimeoutMs = 0;
      // Soft limit for review iterations; on the last attempt reviewer must choose ACCEPTED or
      // REJECTED.
      private int maxReviewAttempts = 3;
      // This model's real context window in tokens; see WorkerProperties#contextWindowTokens.
      private int contextWindowTokens = 0;
    }
  }

  @Setter
  @Getter
  @YamlConfig
  public static class ExtractExternalProperties {

    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;
    private int maxRetries = 1;
    private boolean followRedirects = true;
    private String userAgent = "owlsda/1.0";
    private boolean cacheEnabled = true;
    private long cacheTtlMs = 3600000L;
    private int cacheMaxEntries = 100;
    private String cacheDir = "target/cache/ontology-extract-external";
    private String cacheFormat = "TURTLE";
    private List<MirrorEntry> mirrors = new ArrayList<>();

    @Getter
    @Setter
    @YamlConfig
    public static class MirrorEntry {

      private String uri;
      private List<String> mirrors;
      private String mirror;

      public List<String> getResolvedMirrors() {
        if (mirrors != null && !mirrors.isEmpty()) {
          return mirrors;
        }
        if (mirror != null) {
          return List.of(mirror);
        }
        return List.of();
      }
    }
  }

  @Getter
  @Setter
  @YamlConfig
  public static class ReasonerProperties {

    private String rulesFile = "";
    private String reasonerType = "owl";
    private long reasonerTimeoutMs = 0L;
    private String inferredOutputPath = "";
  }

  @Getter
  @Setter
  @YamlConfig
  public static class ShaclProperties {
    private String outputDir = "";
  }

  public enum DataRichness {
    MINIMAL,
    BALANCED,
    RICH;

    public static DataRichness from(String value) {
      if (value == null || value.isBlank()) {
        return MINIMAL;
      }

      String normalized = value.trim().replace('-', '_').toUpperCase();
      try {
        return DataRichness.valueOf(normalized);
      } catch (IllegalArgumentException ignored) {
        return MINIMAL;
      }
    }
  }

  @Getter
  @Setter
  @YamlConfig
  public static class GenerationProperties {
    // Supported values: minimal, balanced, rich
    private String dataRichness = "minimal";
  }

  /**
   * A short, always-on summary of the ontology (class/property counts and a capped name sample) is
   * generated for every session regardless of this setting. {@code provideFullToWorkers}
   * additionally controls whether workers also receive the complete ontology, serialized to Turtle
   * - the supervisor and reviewer always receive it. Ontologies can run to hundreds of KB, so
   * disabling this saves worker context budget when the summary plus tool-based lookups
   * (context_reader, triplestore_read) are enough.
   */
  @Getter
  @Setter
  @YamlConfig
  public static class OntologyProperties {
    private boolean provideFullToWorkers = true;
  }

  @Getter
  @Setter
  @YamlConfig
  public static class BenchmarkProperties {
    private boolean enabled = false;
    private String outputDir = "target/benchmarks";
    // How often (in seconds) a live snapshot is captured while a round/review iteration is in
    // progress, so message logs and other benchmark output update during a run instead of only
    // at round/iteration boundaries.
    private long liveIntervalSeconds = 15;
  }

  @Getter
  @Setter
  @YamlConfig
  public static class ToolsProperties {
    private RoleToolsProperties worker = new RoleToolsProperties();
    private RoleToolsProperties supervisor = new RoleToolsProperties();
    private RoleToolsProperties reviewer = new RoleToolsProperties();
    private HttpToolProperties http = new HttpToolProperties();
    private MemoryToolProperties memory = new MemoryToolProperties();
  }

  /**
   * Per-role tool enable/disable list. Tool names match {@code SessionHandler.getName()}/{@code
   * NAME} constants. When {@code enabled} is null/empty, all tools are allowed by default; {@code
   * disabled} is then applied on top to subtract specific tools.
   */
  @Getter
  @Setter
  @YamlConfig
  public static class RoleToolsProperties {
    private List<String> enabled;
    private List<String> disabled = new ArrayList<>();
  }

  @Getter
  @Setter
  @YamlConfig
  public static class HttpToolProperties {
    private boolean enabled = true;
    // Additional hosts/URL prefixes to trust, on top of the auto-seeded
    // extract.mirrors + user-context URLs.
    private List<String> allowedHosts = new ArrayList<>();
    // If false, the allowlist is ONLY allowedHosts (no auto-seeding).
    private boolean seedFromExtractMirrors = true;
    private boolean seedFromUserContext = true;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private int maxResponseBodyBytes = 1_000_000;
    private boolean allowPost = true;
    private int maxRetries = 2;
  }

  @Getter
  @Setter
  @YamlConfig
  public static class MemoryToolProperties {
    private boolean enabled = true;
    private int maxEntries = 500;
    private int maxValueBytes = 100_000;
  }

  /**
   * Automatic context compaction for long-running sessions. Applies to the Ollama and
   * openai-compatible providers; see {@link #copilotEnabled}.
   */
  @Getter
  @Setter
  @YamlConfig
  public static class CompactionProperties {
    private boolean enabled = true;
    // Fallback trigger used only when a session's SessionConfig#contextWindowTokens is unset (0):
    // estimated tokens currently in a session's message history. Prefer configuring each role's
    // context-window-tokens instead, so compaction triggers relative to the model's real context
    // window (see #contextWindowThresholdRatio) rather than this guessed absolute count.
    private int tokenThreshold = 6000;
    // Primary trigger when a role's context-window-tokens is configured (>0): compaction fires
    // once the last reported prompt token usage reaches this fraction of the model's real context
    // window, e.g. 0.75 = compact once 75% of the actual context window is used.
    private double contextWindowThresholdRatio = 0.75;
    // Fallback OR-trigger used only when context-window-tokens is unset (0), alongside
    // #tokenThreshold: raw message count. 0 disables this trigger. NOT consulted when
    // context-window-tokens IS configured - tool-call-heavy sessions (many small
    // request/response pairs) hit a raw message count far sooner than they approach real context
    // usage, which previously caused premature compaction despite context-window-tokens and
    // #contextWindowThresholdRatio being configured correctly.
    private int messageCountThreshold = 40;
    // Number of most-recent messages (after the system message) preserved verbatim.
    private int keepRecentMessages = 8;
    // Compaction is implemented for the Ollama and openai-compatible providers; the Copilot SDK
    // manages its own opaque server-side history and only exposes a full reset(), not partial
    // compaction.
    private boolean ollamaEnabled = true;
    private boolean openaiCompatibleEnabled = true;
    private boolean copilotEnabled = false;
  }
}
