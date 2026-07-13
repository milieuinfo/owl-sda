package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured worker progress reporting tool to avoid ambiguous free-text statuses.
 */
public record WorkerProgressHandler(String workerId, Consumer<Context> progressPublisher)
    implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(WorkerProgressHandler.class);
  public static final String CONTEXT_NAME = "Worker Progress Report";
  public static final String NAME = "worker_progress";

  public enum WorkerStatus {
    CREATED,
    FIXED,
    VERIFIED_NO_CHANGE,
    BLOCKED
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Submit structured worker progress for assigned shape/class and validation result.";
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "status", Map.of(
                "type", "string",
                "enum", new String[]{"CREATED", "FIXED", "VERIFIED_NO_CHANGE", "BLOCKED"}
            ),
            "target_shape", Map.of("type", "string"),
            "target_class", Map.of("type", "string"),
            "changed_triples_count", Map.of("type", "integer"),
            "created_or_updated_subjects", Map.of("type", "string"),
            "validation_result", Map.of("type", "string", "enum", new String[]{"CONFORMS", "NON_CONFORMS"}),
            "remaining_issues", Map.of("type", "string")
        ),
        "required", new String[]{
            "status", "target_shape", "target_class", "changed_triples_count",
            "created_or_updated_subjects", "validation_result", "remaining_issues"
        }
    );
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    try {
      WorkerStatus status = WorkerStatus.valueOf(String.valueOf(arguments.get("status")));
      String targetShape = String.valueOf(arguments.get("target_shape"));
      String targetClass = String.valueOf(arguments.get("target_class"));
      int changedTriples = readChangedTriplesCount(arguments.get("changed_triples_count"));
      String subjects = String.valueOf(arguments.get("created_or_updated_subjects"));
      String validationResult = String.valueOf(arguments.get("validation_result"));
      String remainingIssues = String.valueOf(arguments.get("remaining_issues"));

      Context progress = new Context();
      progress.setName(CONTEXT_NAME);
      progress.setType("text/plain");
      progress.setContent(String.join("\n",
          "worker_id=" + workerId,
          "status=" + status,
          "target_shape=" + targetShape,
          "target_class=" + targetClass,
          "changed_triples_count=" + changedTriples,
          "created_or_updated_subjects=" + subjects,
          "validation_result=" + validationResult,
          "remaining_issues=" + remainingIssues
      ));

      if (progressPublisher != null) {
        progressPublisher.accept(progress);
      }

      logger.info("[{}] Worker progress submitted: status={}, shape={}, class={}, changed_triples={}",
          workerId, status, targetShape, targetClass, changedTriples);

      return CompletableFuture.completedFuture(Map.of(
          "status", "success",
          "message", "Worker progress recorded",
          "worker_id", workerId
      ));
    } catch (Exception e) {
      logger.warn("[{}] Invalid worker_progress payload: {}", workerId, e.getMessage());
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "message", "Invalid worker_progress arguments: " + e.getMessage()
      ));
    }
  }

  private int readChangedTriplesCount(Object rawValue) {
    if (rawValue instanceof Number number) {
      return number.intValue();
    }
    if (rawValue instanceof String stringValue) {
      return Integer.parseInt(stringValue.trim());
    }
    throw new IllegalArgumentException("changed_triples_count must be a number or numeric string");
  }
}
