package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervisor-only handler to publish delegation instructions for worker agents.
 */
public record DelegationHandler(DelegationPublisher publishContext) implements SessionHandler {

  /** Canonical name for the context used to carry per-worker delegation instructions. */
  public static final String DELEGATION_CONTEXT_NAME = "Delegation Instructions";
  public static final String NAME = "delegate_tasks";

  private static final Logger logger = LoggerFactory.getLogger(DelegationHandler.class);

  @FunctionalInterface
  public interface DelegationPublisher {
    PublicationResult publish(String contextName, String targetAgent, String instructions);
  }

  public record PublicationResult(boolean success, String resolvedTargetAgent, String message) {

    public static PublicationResult success(String resolvedTargetAgent) {
      return new PublicationResult(true, resolvedTargetAgent, null);
    }

    public static PublicationResult error(String message) {
      return new PublicationResult(false, null, message);
    }

  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "context_name", Map.of(
                "type", "string",
                "description", "Delegation context name (default: Delegation Instructions)"
            ),
            "target_agent", Map.of(
                "type", "string",
                "description", "Target worker agent ID (for example: POOL-0)"
            ),
            "instructions", Map.of(
                "type", "string",
                "description", "Concrete worker instructions for the target agent"
            )
        ),
        "required", List.of("target_agent", "instructions")
    );
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Publish worker delegation instructions to a single target agent.";
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String name = (String) arguments.getOrDefault("context_name", DELEGATION_CONTEXT_NAME);
    String targetAgent = (String) arguments.get("target_agent");
    String instructions = (String) arguments.get("instructions");

    if (targetAgent == null || targetAgent.isBlank()) {
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "message", "target_agent is required"
      ));
    }

    if (instructions == null || instructions.isBlank()) {
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "message", "instructions is required"
      ));
    }

    PublicationResult result = publishContext.publish(name, targetAgent, instructions);
    if (!result.success()) {
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "target_agent", targetAgent,
          "message", result.message()
      ));
    }

    logger.info("Published delegation context '{}' for agent '{}' ({} chars)",
        name, result.resolvedTargetAgent(), instructions.length());

    return CompletableFuture.completedFuture(Map.of(
        "status", "success",
        "context_name", name,
        "target_agent", result.resolvedTargetAgent(),
        "characters", instructions.length()
    ));
  }
}
