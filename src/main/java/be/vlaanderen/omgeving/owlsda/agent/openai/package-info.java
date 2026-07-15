/**
 * {@link be.vlaanderen.omgeving.owlsda.agent.Client}/{@link
 * be.vlaanderen.omgeving.owlsda.agent.Session} implementation backed by any OpenAI Chat
 * Completions-compatible {@code /chat/completions} endpoint ({@code client.provider:
 * "openai-compatible"}) - OpenAI itself, Azure OpenAI, or a self-hosted gateway. Extends {@link
 * be.vlaanderen.omgeving.owlsda.agent.AbstractHttpChatSession}, supplying OpenAI's request/response
 * JSON shape and its bearer-token Authorization header; everything else is shared with {@code
 * agent.ollama}.
 */
package be.vlaanderen.omgeving.owlsda.agent.openai;
