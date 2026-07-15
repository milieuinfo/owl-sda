/**
 * Unchecked exception hierarchy for unrecoverable failures at the LLM and ontology/RDF boundaries:
 * {@link be.vlaanderen.omgeving.owlsda.exception.LanguageModelException} for LLM session/provider
 * failures, and {@link be.vlaanderen.omgeving.owlsda.exception.OntologyException} (with {@link
 * be.vlaanderen.omgeving.owlsda.exception.OntologyLoadException} and {@link
 * be.vlaanderen.omgeving.owlsda.exception.OntologyReasonException}) for ontology loading/reasoning
 * failures. Handlers at the LLM tool-call boundary ({@code agent.handler}) intentionally do not use
 * these - a failed tool call becomes an {@code {"error": ...}} result for the LLM to react to, not
 * a thrown exception.
 */
package be.vlaanderen.omgeving.owlsda.exception;
