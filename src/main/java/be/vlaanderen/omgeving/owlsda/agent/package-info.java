/**
 * Core LLM session abstractions used by every agent role (worker, supervisor, reviewer): {@link
 * be.vlaanderen.omgeving.owlsda.agent.Client} creates {@link
 * be.vlaanderen.omgeving.owlsda.agent.Session}s, {@link
 * be.vlaanderen.omgeving.owlsda.agent.SessionManager} owns the worker pool plus the supervisor and
 * reviewer sessions for a run, and {@link be.vlaanderen.omgeving.owlsda.agent.SessionPool} hands
 * worker sessions out to concurrent workers. {@link
 * be.vlaanderen.omgeving.owlsda.agent.AbstractHttpChatSession} is the shared base for the
 * HTTP-backed providers in {@code agent.ollama} and {@code agent.openai}; {@code agent.copilot}
 * wraps the Copilot SDK instead. Provider-agnostic tool implementations live in {@code
 * agent.handler}, and system/context content lives in {@code agent.context}. See {@code
 * docs/guide/architecture.md} for how these fit into the overall supervisor/worker/reviewer
 * workflow.
 */
package be.vlaanderen.omgeving.owlsda.agent;
