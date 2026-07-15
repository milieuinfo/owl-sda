package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;

/** Finds a named context within a session's current context list. */
final class SessionContextLookup {

  private SessionContextLookup() {}

  /** Returns the content of the context named {@code contextName}, or null if not present. */
  static String findContent(Session session, String contextName) {
    for (Context context : session.getContext()) {
      if (contextName.equals(context.getName())) {
        return context.getContent();
      }
    }
    return null;
  }

  /** Whether the session has a context named {@code contextName} with non-blank content. */
  static boolean hasNonBlankContent(Session session, String contextName) {
    String content = findContent(session, contextName);
    return content != null && !content.isBlank();
  }
}
