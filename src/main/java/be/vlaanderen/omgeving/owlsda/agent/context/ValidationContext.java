package be.vlaanderen.omgeving.owlsda.agent.context;

/**
 * Context for SHACL validation results. Provides validation report as context instead of including
 * it in prompt text.
 */
public class ValidationContext extends Context {

  public ValidationContext(String validationReport) {
    super();
    setName("Validation Report");
    setType("text/plain");
    setContent(validationReport);
  }
}
