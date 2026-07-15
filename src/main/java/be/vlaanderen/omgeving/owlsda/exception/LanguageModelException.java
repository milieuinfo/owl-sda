package be.vlaanderen.omgeving.owlsda.exception;

public class LanguageModelException extends RuntimeException {

  public LanguageModelException(String message) {
    super(message);
  }

  public LanguageModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
