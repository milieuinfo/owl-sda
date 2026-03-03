package be.vlaanderen.omgeving.owlsda.agent;

import lombok.Getter;

/**
 * Represents a response message in the context of an LLM.
 */
@Getter
public class ResponseMessage extends Message {

  public ResponseMessage(String id) {
    this.setMessageId(id);
  }
}
