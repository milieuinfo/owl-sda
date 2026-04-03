package be.vlaanderen.omgeving.owlsda.agent;

import lombok.Getter;
import lombok.Setter;

/**
 * Base class for messages exchanged with an LLM session.
 */
@Getter
@Setter
public class Message {
  private String messageId;
  private String message;
}
