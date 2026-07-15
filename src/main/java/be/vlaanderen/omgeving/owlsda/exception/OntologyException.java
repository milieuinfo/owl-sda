package be.vlaanderen.omgeving.owlsda.exception;

public class OntologyException extends RuntimeException {

  public OntologyException(String message) {
    super(message);
  }

  public OntologyException(String message, Throwable cause) {
    super(message, cause);
  }
}
