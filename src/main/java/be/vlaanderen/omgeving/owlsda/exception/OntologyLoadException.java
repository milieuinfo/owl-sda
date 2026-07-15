package be.vlaanderen.omgeving.owlsda.exception;

public class OntologyLoadException extends OntologyException {

  public OntologyLoadException(String message) {
    super(message);
  }

  public OntologyLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
