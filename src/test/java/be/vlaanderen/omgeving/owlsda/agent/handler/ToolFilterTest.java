package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config.RoleToolsProperties;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolFilterTest {

  @Test
  public void isEnabled_NullProps_ReturnsTrue() {
    assertTrue(ToolFilter.isEnabled(null, "http_call"));
  }

  @Test
  public void isEnabled_DefaultProps_AllowsEverything() {
    RoleToolsProperties props = new RoleToolsProperties();
    assertTrue(ToolFilter.isEnabled(props, "http_call"));
    assertTrue(ToolFilter.isEnabled(props, "memory_set"));
  }

  @Test
  public void isEnabled_NonEmptyAllowlist_OnlyAllowsListedTools() {
    RoleToolsProperties props = new RoleToolsProperties();
    props.setEnabled(List.of("http_call", "memory_set"));

    assertTrue(ToolFilter.isEnabled(props, "http_call"));
    assertFalse(ToolFilter.isEnabled(props, "triplestore_add"));
  }

  @Test
  public void isEnabled_DenylistOverridesAllowlist_WhenToolIsDisabled() {
    RoleToolsProperties props = new RoleToolsProperties();
    props.setEnabled(List.of("http_call", "memory_set"));
    props.setDisabled(List.of("http_call"));

    assertFalse(ToolFilter.isEnabled(props, "http_call"));
    assertTrue(ToolFilter.isEnabled(props, "memory_set"));
  }

  @Test
  public void isEnabled_DisabledOnly_RemovesJustThatTool() {
    RoleToolsProperties props = new RoleToolsProperties();
    props.setDisabled(List.of("http_call"));

    assertFalse(ToolFilter.isEnabled(props, "http_call"));
    assertTrue(ToolFilter.isEnabled(props, "memory_set"));
  }
}
