package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config.RoleToolsProperties;

/**
 * Decides whether a named tool should be registered for a given role, based on the
 * {@code tools.<role>.enabled}/{@code tools.<role>.disabled} configuration lists.
 */
public final class ToolFilter {

  private ToolFilter() {
  }

  /**
   * A tool is enabled when: it is present in {@code enabled} (or {@code enabled} is null/empty,
   * meaning "all tools allowed"), AND it is not present in {@code disabled}.
   */
  public static boolean isEnabled(RoleToolsProperties props, String toolName) {
    if (props == null) {
      return true;
    }

    if (props.getEnabled() != null && !props.getEnabled().isEmpty()
        && !props.getEnabled().contains(toolName)) {
      return false;
    }

    return props.getDisabled() == null || !props.getDisabled().contains(toolName);
  }
}
