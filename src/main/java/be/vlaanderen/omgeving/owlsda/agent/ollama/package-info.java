/**
 * {@link be.vlaanderen.omgeving.owlsda.agent.Client}/{@link
 * be.vlaanderen.omgeving.owlsda.agent.Session} implementation backed by a self-hosted Ollama
 * server's {@code /api/chat} endpoint ({@code client.provider: "ollama"}). Extends {@link
 * be.vlaanderen.omgeving.owlsda.agent.AbstractHttpChatSession}, supplying Ollama's request/response
 * JSON shape and its {@code think} (chain-of-thought) flag; everything else - context tracking,
 * retries, history compaction, tool dispatch - is shared with {@code agent.openai}.
 */
package be.vlaanderen.omgeving.owlsda.agent.ollama;
