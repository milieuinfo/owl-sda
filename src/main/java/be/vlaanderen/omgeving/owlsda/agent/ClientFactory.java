package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.copilot.CopilotSDKClient;
import be.vlaanderen.omgeving.owlsda.agent.ollama.OllamaClient;
import be.vlaanderen.omgeving.owlsda.agent.openai.OpenAiCompatibleClient;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.util.Locale;

/** Factory for creating LLM clients based on configured provider. */
public final class ClientFactory {

  public enum Role {
    WORKER,
    SUPERVISOR,
    REVIEWER
  }

  private ClientFactory() {}

  public static Client create(Config config) {
    String provider = resolveProvider(config);
    return create(config, provider);
  }

  public static Client create(Config config, String provider) {
    String normalizedProvider = normalizeProvider(provider);
    return switch (normalizedProvider) {
      case "copilot" -> new CopilotSDKClient();
      case "ollama" -> new OllamaClient(config);
      case "openai-compatible" -> new OpenAiCompatibleClient(config);
      default ->
          throw new IllegalArgumentException("Unsupported client provider: " + normalizedProvider);
    };
  }

  static String resolveProvider(Config config) {
    if (config == null || config.getClient() == null) {
      return "copilot";
    }

    return normalizeProvider(config.getClient().getProvider());
  }

  static String resolveProvider(Config config, Role role) {
    if (role == null) {
      return resolveProvider(config);
    }

    if (config == null || config.getClient() == null) {
      return "copilot";
    }

    String roleProvider =
        switch (role) {
          case WORKER ->
              config.getClient().getWorker() != null
                  ? config.getClient().getWorker().getProvider()
                  : null;
          case SUPERVISOR ->
              config.getClient().getSupervisor() != null
                  ? config.getClient().getSupervisor().getProvider()
                  : null;
          case REVIEWER ->
              config.getClient().getReviewer() != null
                  ? config.getClient().getReviewer().getProvider()
                  : null;
        };

    if (roleProvider == null || roleProvider.isBlank()) {
      return resolveProvider(config);
    }

    return normalizeProvider(roleProvider);
  }

  private static String normalizeProvider(String provider) {
    if (provider == null) {
      return "copilot";
    }

    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank() ? "copilot" : normalized;
  }
}
