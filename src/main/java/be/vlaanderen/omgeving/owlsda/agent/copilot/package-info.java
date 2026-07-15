/**
 * {@link be.vlaanderen.omgeving.owlsda.agent.Client}/{@link
 * be.vlaanderen.omgeving.owlsda.agent.Session} implementation backed by the GitHub Copilot SDK
 * ({@code client.provider: "copilot"}, the project's default). Unlike the HTTP-based {@code
 * agent.ollama}/{@code agent.openai} providers, this one is event-driven around {@code
 * CopilotSession}; {@link be.vlaanderen.omgeving.owlsda.agent.copilot.CopilotSDKSessionHandler}
 * adapts this project's {@link be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler} tool
 * contract to the SDK's own tool-call callback shape.
 */
package be.vlaanderen.omgeving.owlsda.agent.copilot;
