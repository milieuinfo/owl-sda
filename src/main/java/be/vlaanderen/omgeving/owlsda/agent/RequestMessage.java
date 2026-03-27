package be.vlaanderen.omgeving.owlsda.agent;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a request message in the context of an LLM.
 */
public class RequestMessage extends Message {
  @Getter
  @Setter
  private String instructionKey;

  public RequestMessage(String prompt) {
    this.setMessage(prompt);
  }

  public RequestMessage(String prompt, String instructionKey) {
    this.setMessage(prompt);
    this.instructionKey = instructionKey;
  }
}
