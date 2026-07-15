/**
 * Orchestrates the multi-agent generation workflow. {@link
 * be.vlaanderen.omgeving.owlsda.generation.SupervisorWorkflow} drives the round loop; each round,
 * {@link be.vlaanderen.omgeving.owlsda.generation.Supervisor} prompts the supervisor LLM to
 * delegate work, then hands off to {@link
 * be.vlaanderen.omgeving.owlsda.generation.ConcurrentWorkerBatch}, which fans {@link
 * be.vlaanderen.omgeving.owlsda.generation.WorkerAgent} tasks out across the worker pool. {@link
 * be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator} runs the separate
 * accept/reject/revise review loop once generation finishes. See {@code docs/guide/architecture.md}
 * for the full round-by-round flow.
 */
package be.vlaanderen.omgeving.owlsda.generation;
