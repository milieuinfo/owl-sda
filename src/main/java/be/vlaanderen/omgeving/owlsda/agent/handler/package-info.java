/**
 * Tool-call handlers exposed to LLM sessions. Every class implements {@link
 * be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler}, describing itself (name,
 * description, JSON-schema arguments) and handling invocations as a {@code
 * CompletableFuture<Object>} tool result; handler failures are caught and returned as {@code
 * {"error": ...}} results rather than thrown, since a failed tool call is a normal outcome the LLM
 * should be able to react to. {@link be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore}
 * is the shared RDF store all worker sessions write into; {@link
 * be.vlaanderen.omgeving.owlsda.agent.handler.ToolFilter} gates which handlers are registered per
 * role based on {@code Config}.
 */
package be.vlaanderen.omgeving.owlsda.agent.handler;
