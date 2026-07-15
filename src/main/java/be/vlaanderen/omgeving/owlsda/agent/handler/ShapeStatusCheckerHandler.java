package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.generation.ShapeProcessingTracker;
import be.vlaanderen.omgeving.owlsda.generation.ShapeProcessingTracker.ShapeProcessingStatus;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool handler for supervisors to check which shapes have been completed by workers. Provides
 * total/completed/remaining counts and the list of completed shape names.
 */
public record ShapeStatusCheckerHandler(ShapeProcessingTracker tracker) implements SessionHandler {

  public static final String NAME = "check_shape_status";

  private static final Logger logger = LoggerFactory.getLogger(ShapeStatusCheckerHandler.class);

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Check the processing status of shapes. Shows how many shapes have been completed by "
        + "workers versus how many remain. Use this to verify that workers have completed their "
        + "assigned tasks before proceeding.";
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "format",
        Map.of(
            "type",
            "string",
            "enum",
            new String[] {"summary", "detailed"},
            "description",
            "Output format: 'summary' for counts only, 'detailed' for full shape lists",
            "default",
            "detailed"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String format = (String) arguments.getOrDefault("format", "detailed");

            ShapeProcessingStatus status = tracker.getStatus();

            if ("summary".equals(format)) {
              return buildSummaryReport(status);
            } else {
              return tracker.getStatusReport();
            }
          } catch (Exception e) {
            logger.error("Error checking shape status", e);
            return "Error checking shape status: " + e.getMessage();
          }
        });
  }

  private String buildSummaryReport(ShapeProcessingStatus status) {
    StringBuilder report = new StringBuilder();
    report.append("Shape Processing Summary:\n");
    report.append(String.format("- Total: %d shapes\n", status.getTotalShapes()));
    report.append(
        String.format(
            "- Completed: %d (%.1f%%)\n",
            status.getCompletedCount(),
            status.getTotalShapes() > 0
                ? (status.getCompletedCount() * 100.0 / status.getTotalShapes())
                : 0));
    report.append(String.format("- Remaining: %d\n", status.getRemainingCount()));

    if (tracker.allShapesCompleted()) {
      report.append("\nAll shapes have been completed by workers.");
    } else {
      report.append(
          String.format("\n⚠ %d shapes still remain to be completed.", status.getRemainingCount()));
    }

    return report.toString();
  }
}
