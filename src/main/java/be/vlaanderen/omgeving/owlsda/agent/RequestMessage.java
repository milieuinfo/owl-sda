package be.vlaanderen.omgeving.owlsda.agent;

/**
 * Represents a request message in the context of an LLM.
 */
public class RequestMessage extends Message {

  public RequestMessage(String prompt) {
    this.setMessage(prompt);
  }

}
