package be.vlaanderen.omgeving.owlsda.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OpenAiCompatiblePropertiesTest {

  @Test
  public void resolveApiKey_PrefersConfigValueOverEnvFallback() {
    Config.ClientProperties.OpenAiCompatibleProperties props =
        new Config.ClientProperties.OpenAiCompatibleProperties();
    props.setApiKey("sk-config-value");

    assertEquals("sk-config-value", props.resolveApiKey("sk-env-value"));
  }

  @Test
  public void resolveApiKey_FallsBackToEnvWhenConfigBlank() {
    Config.ClientProperties.OpenAiCompatibleProperties props =
        new Config.ClientProperties.OpenAiCompatibleProperties();
    props.setApiKey("   ");

    assertEquals("sk-env-value", props.resolveApiKey("sk-env-value"));
  }

  @Test
  public void resolveApiKey_ReturnsNullWhenNeitherSet() {
    Config.ClientProperties.OpenAiCompatibleProperties props =
        new Config.ClientProperties.OpenAiCompatibleProperties();

    assertNull(props.resolveApiKey(null));
  }
}
