package be.vlaanderen.omgeving.owlsda.agent.ollama;

import be.vlaanderen.omgeving.owlsda.agent.Client;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama-backed implementation of the OWL-SDA LLM client.
 */
public class OllamaClient implements Client {
  private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);

  private final HttpClient httpClient;
  private final String baseUrl;
  private final Config.CompactionProperties compactionProperties;
  private final boolean think;
  private final List<Session> sessions = new ArrayList<>();

  public OllamaClient(Config config) {
    String configuredBaseUrl = "http://localhost:11434";
    boolean configuredThink = true;
    if (config != null
        && config.getClient() != null
        && config.getClient().getOllama() != null) {
      if (config.getClient().getOllama().getBaseUrl() != null
          && !config.getClient().getOllama().getBaseUrl().isBlank()) {
        configuredBaseUrl = config.getClient().getOllama().getBaseUrl().trim();
      }
      configuredThink = config.getClient().getOllama().isThink();
    }

    this.baseUrl = configuredBaseUrl.endsWith("/")
        ? configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1)
        : configuredBaseUrl;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    this.compactionProperties = config != null
        ? config.getCompaction()
        : new Config.CompactionProperties();
    this.think = configuredThink;

    logger.info("Initialized Ollama client with base URL {}", this.baseUrl);
  }

  @Override
  public String getName() {
    return "ollama";
  }

  @Override
  public Session createSession(be.vlaanderen.omgeving.owlsda.agent.SessionConfig config)
      throws ExecutionException, InterruptedException {
    OllamaSession session = new OllamaSession(config, baseUrl, httpClient, compactionProperties, think);
    sessions.add(session);
    return session;
  }

  @Override
  public List<Session> getSessions() {
    return sessions;
  }

  @Override
  public void close() {
    sessions.forEach(Session::close);
  }
}

