/**
 * Content attached to an LLM session's prompt window. {@link
 * be.vlaanderen.omgeving.owlsda.agent.context.Context} is the base unit (a named, typed piece of
 * content, e.g. an ontology excerpt or a validation report); {@code agent.context.ContextFactory}
 * builds the fixed per-role system instructions read from {@code src/main/resources}, and the other
 * {@code *Context} classes render dynamic content (SHACL shapes, the current output file,
 * validation reports) on demand.
 */
package be.vlaanderen.omgeving.owlsda.agent.context;
