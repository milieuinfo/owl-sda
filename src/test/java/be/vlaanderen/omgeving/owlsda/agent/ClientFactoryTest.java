package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.config.Config;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClientFactoryTest {

  @Test
  public void resolveProvider_WhenUnset_DefaultsToCopilot() {
    Config config = new Config();

    assertEquals("copilot", ClientFactory.resolveProvider(config));
  }

  @Test
  public void resolveProvider_NormalizesWhitespaceAndCase() {
    Config config = new Config();
    config.getClient().setProvider("  OLLAMA  ");

    assertEquals("ollama", ClientFactory.resolveProvider(config));
  }

  @Test
  public void resolveProvider_WithRoleOverride_UsesRoleProvider() {
    Config config = new Config();
    config.getClient().setProvider("copilot");
    config.getClient().getWorker().setProvider("  OLLAMA  ");

    assertEquals("ollama", ClientFactory.resolveProvider(config, ClientFactory.Role.WORKER));
  }

  @Test
  public void resolveProvider_WithBlankRoleOverride_FallsBackToGlobal() {
    Config config = new Config();
    config.getClient().setProvider("  OLLAMA  ");
    config.getClient().getSupervisor().setProvider("   ");

    assertEquals("ollama", ClientFactory.resolveProvider(config, ClientFactory.Role.SUPERVISOR));
  }

  @Test
  public void resolveProvider_NormalizesOpenAiCompatible() {
    Config config = new Config();
    config.getClient().setProvider("  OPENAI-COMPATIBLE  ");

    assertEquals("openai-compatible", ClientFactory.resolveProvider(config));
  }

  @Test
  public void resolveProvider_WithRoleOverride_UsesOpenAiCompatible() {
    Config config = new Config();
    config.getClient().setProvider("copilot");
    config.getClient().getWorker().setProvider("openai-compatible");

    assertEquals("openai-compatible", ClientFactory.resolveProvider(config, ClientFactory.Role.WORKER));
  }
}

