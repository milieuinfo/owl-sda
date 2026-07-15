package be.vlaanderen.omgeving.owlsda.config;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class ConfigClientProviderTest {

  @Test
  public void loadFile_WithClientProviderAndOllamaBaseUrl_MapsValues() throws IOException {
    Path configFile = Files.createTempFile("owlsda-client-provider", ".yml");
    Files.writeString(
        configFile,
        """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        client:
          provider: "ollama"
          ollama:
            base-url: "http://localhost:11434"
          worker:
            provider: "ollama"
          supervisor:
            provider: "copilot"
        """,
        StandardCharsets.UTF_8);

    Config config = Config.loadFile(configFile.toString());

    assertEquals("ollama", config.getClient().getProvider());
    assertEquals("http://localhost:11434", config.getClient().getOllama().getBaseUrl());
    assertEquals("ollama", config.getClient().getWorker().getProvider());
    assertEquals("copilot", config.getClient().getSupervisor().getProvider());
  }

  @Test
  public void loadFile_WithOpenAiCompatibleProperties_MapsValues() throws IOException {
    Path configFile = Files.createTempFile("owlsda-client-provider", ".yml");
    Files.writeString(
        configFile,
        """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        client:
          provider: "openai-compatible"
          openai-compatible:
            base-url: "https://api.openai.com/v1"
            api-key: "sk-test-123"
        """,
        StandardCharsets.UTF_8);

    Config config = Config.loadFile(configFile.toString());

    assertEquals("openai-compatible", config.getClient().getProvider());
    assertEquals(
        "https://api.openai.com/v1", config.getClient().getOpenaiCompatible().getBaseUrl());
    assertEquals("sk-test-123", config.getClient().getOpenaiCompatible().getApiKey());
  }
}
