package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which shapes have been delegated to workers and their processing status.
 * Provides methods for supervisors to verify worker completion.
 */
public class ShapeProcessingTracker {
  private static final Logger logger = LoggerFactory.getLogger(ShapeProcessingTracker.class);

  private final Shacl shacl;

  // Map of shape name to worker ID that was assigned
  private final Map<String, String> shapeAssignments = new HashMap<>();

  // Set of shape names that have been marked as completed
  private final Set<String> completedShapes = new HashSet<>();

  // Set of shape names that have been delegated but not yet completed
  private final Set<String> pendingShapes = new HashSet<>();

  public ShapeProcessingTracker(Shacl shacl) {
    this.shacl = shacl;
  }

  /**
   * Records that a shape has been delegated to a specific worker.
   *
   * @param shapeName The name of the shape
   * @param workerId The worker ID (e.g., "POOL-0")
   */
  public synchronized void recordDelegation(String shapeName, String workerId) {
    shapeAssignments.put(shapeName, workerId);
    pendingShapes.add(shapeName);
    logger.debug("Shape '{}' delegated to worker {}", shapeName, workerId);
  }

  /**
   * Records that a shape has been delegated to multiple workers.
   *
   * @param shapeNames List of shape names
   * @param workerId The worker ID
   */
  public synchronized void recordDelegations(List<String> shapeNames, String workerId) {
    for (String shapeName : shapeNames) {
      recordDelegation(shapeName, workerId);
    }
  }

  /**
   * Marks a shape as completed/processed.
   *
   * @param shapeName The name of the shape
   */
  public synchronized void markCompleted(String shapeName) {
    completedShapes.add(shapeName);
    pendingShapes.remove(shapeName);

    // Also mark in SHACL shape object if available
    if (shacl != null && shacl.getShapes() != null) {
      shacl.getShapes().stream()
          .filter(shape -> shape.getName().equals(shapeName))
          .findFirst()
          .ifPresent(shape -> shape.setProcessed(true));
    }

    logger.info("Shape '{}' marked as completed", shapeName);
  }

  /**
   * Marks multiple shapes as completed.
   *
   * @param shapeNames List of shape names
   */
  public synchronized void markCompleted(List<String> shapeNames) {
    for (String shapeName : shapeNames) {
      markCompleted(shapeName);
    }
  }

  /**
   * Gets the status of all shapes for supervisor review.
   *
   * @return ShapeProcessingStatus containing all processing information
   */
  public synchronized ShapeProcessingStatus getStatus() {
    int totalShapes = shacl != null && shacl.getShapes() != null ? shacl.getShapes().size() : 0;

    return new ShapeProcessingStatus(
        totalShapes,
        completedShapes.size(),
        pendingShapes.size(),
        totalShapes - completedShapes.size() - pendingShapes.size(),
        new ArrayList<>(completedShapes),
        new ArrayList<>(pendingShapes),
        new HashMap<>(shapeAssignments)
    );
  }

  /**
   * Gets a formatted report of shape processing status.
   *
   * @return Human-readable status report
   */
  public synchronized String getStatusReport() {
    ShapeProcessingStatus status = getStatus();

    StringBuilder report = new StringBuilder();
    report.append("=== SHAPE PROCESSING STATUS ===\n\n");
    report.append(String.format("Total Shapes: %d\n", status.getTotalShapes()));
    report.append(String.format("Completed: %d (%.1f%%)\n",
        status.getCompletedCount(),
        status.getTotalShapes() > 0 ? (status.getCompletedCount() * 100.0 / status.getTotalShapes()) : 0));
    report.append(String.format("Pending: %d\n", status.getPendingCount()));
    report.append(String.format("Not Delegated: %d\n\n", status.getNotDelegatedCount()));

    if (!status.getPendingShapes().isEmpty()) {
      report.append("PENDING SHAPES:\n");
      for (String shapeName : status.getPendingShapes()) {
        String workerId = status.getShapeAssignments().get(shapeName);
        report.append(String.format("  - %s (assigned to: %s)\n", shapeName, workerId));
      }
      report.append("\n");
    }

    if (!status.getCompletedShapes().isEmpty()) {
      report.append("COMPLETED SHAPES:\n");
      for (String shapeName : status.getCompletedShapes()) {
        String workerId = status.getShapeAssignments().get(shapeName);
        report.append(String.format("  - %s (completed by: %s)\n", shapeName, workerId));
      }
      report.append("\n");
    }

    // Show not delegated shapes
    if (status.getNotDelegatedCount() > 0 && shacl != null && shacl.getShapes() != null) {
      report.append("NOT YET DELEGATED:\n");
      shacl.getShapes().stream()
          .filter(shape -> !status.getShapeAssignments().containsKey(shape.getName()))
          .forEach(shape -> report.append(String.format("  - %s\n", shape.getName())));
      report.append("\n");
    }

    return report.toString();
  }

  /**
   * Checks if all delegated shapes have been completed.
   *
   * @return true if no pending shapes remain
   */
  public synchronized boolean allDelegatedShapesCompleted() {
    return pendingShapes.isEmpty();
  }

  /**
   * Resets all tracking data (for new generation runs).
   */
  public synchronized void reset() {
    shapeAssignments.clear();
    completedShapes.clear();
    pendingShapes.clear();
    logger.info("Shape processing tracker reset");
  }

  /**
   * Data class containing shape processing status information.
   */
  @Getter
  public static class ShapeProcessingStatus {
    private final int totalShapes;
    private final int completedCount;
    private final int pendingCount;
    private final int notDelegatedCount;
    private final List<String> completedShapes;
    private final List<String> pendingShapes;
    private final Map<String, String> shapeAssignments;

    public ShapeProcessingStatus(
        int totalShapes,
        int completedCount,
        int pendingCount,
        int notDelegatedCount,
        List<String> completedShapes,
        List<String> pendingShapes,
        Map<String, String> shapeAssignments) {
      this.totalShapes = totalShapes;
      this.completedCount = completedCount;
      this.pendingCount = pendingCount;
      this.notDelegatedCount = notDelegatedCount;
      this.completedShapes = completedShapes;
      this.pendingShapes = pendingShapes;
      this.shapeAssignments = shapeAssignments;
    }
  }
}

