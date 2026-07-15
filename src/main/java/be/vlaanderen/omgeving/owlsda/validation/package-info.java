/**
 * Validates generated output against SHACL shapes. {@link
 * be.vlaanderen.omgeving.owlsda.validation.OutputValidator} reads the configured output file, runs
 * it through {@link be.vlaanderen.omgeving.owlsda.ontology.Shacl#validate}, and renders a
 * human-readable violation report consumed by both {@code generation} (to decide whether a round
 * needs another pass) and {@code agent.handler.OutputValidatorHandler} (the LLM-facing tool).
 */
package be.vlaanderen.omgeving.owlsda.validation;
