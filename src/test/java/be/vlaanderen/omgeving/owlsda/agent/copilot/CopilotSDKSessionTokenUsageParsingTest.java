package be.vlaanderen.omgeving.owlsda.agent.copilot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CopilotSDKSessionTokenUsageParsingTest {

  @Test
  public void parseTokenValue_AcceptsNumberValues() {
    assertEquals(Long.valueOf(42L), CopilotSDKSession.parseTokenValue(42));
    assertEquals(Long.valueOf(43L), CopilotSDKSession.parseTokenValue(42.6));
  }

  @Test
  public void parseTokenValue_AcceptsNumericStrings() {
    assertEquals(Long.valueOf(123L), CopilotSDKSession.parseTokenValue("123"));
    assertEquals(Long.valueOf(124L), CopilotSDKSession.parseTokenValue("123.5"));
    assertEquals(Long.valueOf(7L), CopilotSDKSession.parseTokenValue(" 7 "));
  }

  @Test
  public void parseTokenValue_ReturnsNullForUnsupportedValues() {
    assertNull(CopilotSDKSession.parseTokenValue(""));
    assertNull(CopilotSDKSession.parseTokenValue("abc"));
    assertNull(CopilotSDKSession.parseTokenValue(new Object()));
  }
}

