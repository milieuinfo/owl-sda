package be.vlaanderen.omgeving.owlsda.agent.openai;

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
 * Client for any OpenAI Chat Completions-compatible endpoint (BYOK): OpenAI itself, Azure OpenAI,
 * OpenRouter, self-hosted gateways (vLLM, LM Studio, etc.).
 */
public class OpenAiCompatibleClient implements Client {
  private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

  private final HttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final List<Session> sessions = new ArrayList<>();

  public OpenAiCompatibleClient(Config config) {
    String configuredBaseUrl = "https://api.openai.com/v1";
    String resolvedApiKey = null;

    if (config != null && config.getClient() != null && config.getClient().getOpenaiCompatible() != null) {
      Config.ClientProperties.OpenAiCompatibleProperties props = config.getClient().getOpenaiCompatible();
      if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
        configuredBaseUrl = props.getBaseUrl().trim();
      }
      resolvedApiKey = props.resolveApiKey();
    }

    if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
      throw new IllegalStateException(
          "No API key configured for the openai-compatible provider. Set client.openai-compatible.api-key "
              + "in the config file or the OPENAI_API_KEY environment variable.");
    }

    this.baseUrl = configuredBaseUrl.endsWith("/")
        ? configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1)
        : configuredBaseUrl;
    this.apiKey = resolvedApiKey;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    logger.info("Initialized OpenAI-compatible client with base URL {}", this.baseUrl);
  }

  @Override
  public String getName() {
    return "openai-compatible";
  }

  @Override
  public Session createSession(be.vlaanderen.omgeving.owlsda.agent.SessionConfig config)
      throws ExecutionException, InterruptedException {
    OpenAiCompatibleSession session = new OpenAiCompatibleSession(config, baseUrl, apiKey, httpClient);
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
